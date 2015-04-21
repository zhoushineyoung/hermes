package com.ctrip.hermes.meta.service;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.apache.avro.Schema.Parser;
import org.apache.avro.compiler.specific.SpecificCompiler;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.unidal.dal.jdbc.DalException;
import org.unidal.dal.jdbc.DalNotFoundException;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.annotation.Named;

import com.ctrip.hermes.core.meta.MetaManager;
import com.ctrip.hermes.core.meta.MetaService;
import com.ctrip.hermes.meta.dal.meta.Schema;
import com.ctrip.hermes.meta.dal.meta.SchemaDao;
import com.ctrip.hermes.meta.dal.meta.SchemaEntity;
import com.ctrip.hermes.meta.entity.Topic;
import com.ctrip.hermes.meta.pojo.SchemaView;
import com.ctrip.hermes.meta.server.MetaPropertiesLoader;
import com.google.common.io.ByteStreams;

@Named
public class SchemaService {

	private SchemaRegistryClient avroSchemaRegistry;

	private Properties m_properties = MetaPropertiesLoader.load();

	@Inject
	private SchemaDao m_schemaDao;

	@Inject(ServerMetaManager.ID)
	private MetaManager m_metaManager;

	@Inject
	private MetaService m_metaService;

	@Inject
	private TopicService m_topicService;

	@Inject
	private CompileService m_compileService;

	public SchemaService() {
		String schemaServerHost = m_properties.getProperty("schema-server-host");
		String schemaServerPort = m_properties.getProperty("schema-server-port");
		avroSchemaRegistry = new CachedSchemaRegistryClient("http://" + schemaServerHost + ":" + schemaServerPort, 1000);
	}

	/**
	 * 
	 * @param metaSchema
	 * @param avroSchema
	 * @throws IOException
	 * @throws DalException
	 */
	public void compileAvro(Schema metaSchema, org.apache.avro.Schema avroSchema) throws IOException, DalException {
		final Path destDir = Files.createTempDirectory("avroschema");
		SpecificCompiler compiler = new SpecificCompiler(avroSchema);
		compiler.compileToDestination(null, destDir.toFile());

		m_compileService.compile(destDir);
		Path jarFile = Files.createTempFile(metaSchema.getName(), ".jar");
		m_compileService.jar(destDir, jarFile);

		byte[] jarContent = Files.readAllBytes(jarFile);
		metaSchema.setJarContent(jarContent);
		FormDataContentDisposition disposition = FormDataContentDisposition.name(metaSchema.getName())
		      .creationDate(new Date(System.currentTimeMillis()))
		      .fileName(metaSchema.getName() + "_" + metaSchema.getVersion() + ".jar").size(jarFile.toFile().length())
		      .build();
		metaSchema.setJarProperties(disposition.toString());
		m_schemaDao.updateByPK(metaSchema, SchemaEntity.UPDATESET_FULL);
		Files.delete(jarFile);
		m_compileService.delete(destDir);
	}

	/**
	 * 
	 * @param schemaView
	 * @return
	 * @throws DalException
	 * @throws RestClientException
	 * @throws IOException
	 */
	public SchemaView createSchema(SchemaView schemaView) throws DalException, IOException, RestClientException {
		return createSchema(schemaView, -1);
	}

	/**
	 * 
	 * @param schemaView
	 * @param topicId
	 * @return
	 * @throws DalException
	 * @throws RestClientException
	 * @throws IOException
	 */
	public SchemaView createSchema(SchemaView schemaView, long topicId) throws DalException, IOException,
	      RestClientException {
		Schema schema = schemaView.toMetaSchema();
		schema.setCreateTime(new Date(System.currentTimeMillis()));
		try {
			Schema latestSchemaMeta = findLatestSchemaMeta(schema.getName());
			schema.setVersion(latestSchemaMeta.getVersion() + 1);
		} catch (DalNotFoundException e) {
			schema.setVersion(1);
		}
		m_schemaDao.insert(schema);

		Topic topic = null;
		if (topicId > 0) {
			topic = m_metaService.findTopic(topicId);
		}
		if (topic != null) {
			topic.setSchemaId(schema.getId());
			m_topicService.updateTopic(topic);
		}

		if ("avro".equals(schema.getType())) {
			this.avroSchemaRegistry.updateCompatibility(schema.getName(), schema.getCompatibility());
		}

		return new SchemaView(schema);
	}

	public SchemaView updateSchemaFile(SchemaView schemaView, InputStream fileInputStream,
	      FormDataContentDisposition fileHeader) throws IOException, DalException, RestClientException {
		SchemaView result = null;
		if (schemaView.getType().equals("json")) {
			result = uploadJsonSchema(schemaView, null, null, fileInputStream, fileHeader);
		} else if (schemaView.getType().equals("avro")) {
			result = uploadAvroSchema(schemaView, fileInputStream, fileHeader, null, null);
		}
		return result;
	}

	/**
	 * 
	 * @param id
	 * @throws DalException
	 */
	public void deleteSchema(long id) throws DalException {
		Schema schema = m_schemaDao.findByPK(id, SchemaEntity.READSET_FULL);
		m_schemaDao.deleteByPK(schema);
	}

	/**
	 * 
	 * @param schemaName
	 * @return
	 * @throws DalException
	 */
	public Schema findLatestSchemaMeta(String schemaName) throws DalException {
		Schema schema = m_schemaDao.findLatestByName(schemaName, SchemaEntity.READSET_FULL);
		return schema;
	}

	/**
	 * 
	 * @param schemaName
	 * @return
	 * @throws DalException
	 */
	public List<Schema> findSchemaMeta(String schemaName) throws DalException {
		List<Schema> schemas = m_schemaDao.findByName(schemaName, SchemaEntity.READSET_FULL);
		return schemas;
	}

	/**
	 * 
	 * @param schemaId
	 * @return
	 * @throws DalException
	 */
	public Schema getSchemaMeta(long schemaId) throws DalException {
		Schema schema = m_schemaDao.findByPK(schemaId, SchemaEntity.READSET_FULL);
		return schema;
	}

	/**
	 * 
	 * @param schemaId
	 * @return
	 * @throws DalException
	 * @throws IOException
	 * @throws RestClientException
	 */
	public SchemaView getSchemaView(long schemaId) throws DalException, IOException, RestClientException {
		Schema schema = getSchemaMeta(schemaId);
		SchemaView schemaView = new SchemaView(schema);
		return schemaView;
	}

	/**
	 * 
	 * @return
	 * @throws DalException
	 */
	public List<Schema> listLatestSchemaMeta() throws DalException {
		List<Schema> schemas = m_schemaDao.listLatest(SchemaEntity.READSET_FULL);
		return schemas;
	}

	/**
	 * 
	 * @param schemaView
	 * @param schemaInputStream
	 * @param schemaHeader
	 * @param jarInputStream
	 * @param jarHeader
	 * @throws IOException
	 * @throws DalException
	 * @throws RestClientException
	 */
	public SchemaView uploadAvroSchema(SchemaView schemaView, InputStream schemaInputStream,
	      FormDataContentDisposition schemaHeader, InputStream jarInputStream, FormDataContentDisposition jarHeader)
	      throws IOException, DalException, RestClientException {
		Schema metaSchema = schemaView.toMetaSchema();
		Schema updatedSchema = new Schema();
		updatedSchema.setName(metaSchema.getName());
		updatedSchema.setCompatibility(metaSchema.getCompatibility());
		updatedSchema.setCreateTime(new Date(System.currentTimeMillis()));
		updatedSchema.setDescription(metaSchema.getDescription());
		updatedSchema.setType(metaSchema.getType());
		updatedSchema.setVersion(metaSchema.getVersion() + 1);

		if (schemaInputStream != null) {
			byte[] schemaContent = ByteStreams.toByteArray(schemaInputStream);
			updatedSchema.setSchemaContent(schemaContent);
			updatedSchema.setSchemaProperties(schemaHeader.toString());

			Parser parser = new Parser();
			org.apache.avro.Schema avroSchema = parser.parse(new String(schemaContent));
			int avroid = avroSchemaRegistry.register(updatedSchema.getName(), avroSchema);
			updatedSchema.setAvroid(avroid);

			compileAvro(updatedSchema, avroSchema);
		}
		if (jarInputStream != null) {
			byte[] jarContent = ByteStreams.toByteArray(jarInputStream);
			updatedSchema.setJarContent(jarContent);
			updatedSchema.setJarProperties(jarHeader.toString());
		}
		m_schemaDao.insert(updatedSchema);
		return new SchemaView(updatedSchema);
	}

	/**
	 * 
	 * @param schemaView
	 * @param schemaInputStream
	 * @param schemaHeader
	 * @param jarInputStream
	 * @param jarHeader
	 * @throws IOException
	 * @throws DalException
	 */
	public SchemaView uploadJsonSchema(SchemaView schemaView, InputStream schemaInputStream,
	      FormDataContentDisposition schemaHeader, InputStream jarInputStream, FormDataContentDisposition jarHeader)
	      throws IOException, DalException {
		Schema metaSchema = schemaView.toMetaSchema();
		Schema updatedSchema = new Schema();
		updatedSchema.setName(metaSchema.getName());
		updatedSchema.setCompatibility(metaSchema.getCompatibility());
		updatedSchema.setCreateTime(new Date(System.currentTimeMillis()));
		updatedSchema.setDescription(metaSchema.getDescription());
		updatedSchema.setType(metaSchema.getType());
		updatedSchema.setAvroid(metaSchema.getAvroid());
		updatedSchema.setVersion(metaSchema.getVersion() + 1);

		if (schemaInputStream != null) {
			byte[] schemaContent = ByteStreams.toByteArray(schemaInputStream);
			updatedSchema.setSchemaContent(schemaContent);
			updatedSchema.setSchemaProperties(schemaHeader.toString());
		}

		if (jarInputStream != null) {
			byte[] jarContent = ByteStreams.toByteArray(jarInputStream);
			updatedSchema.setJarContent(jarContent);
			updatedSchema.setJarProperties(jarHeader.toString());
		}
		m_schemaDao.insert(updatedSchema);
		return new SchemaView(updatedSchema);
	}

	/**
	 * 
	 * @param name
	 * @param fileInputStream
	 * @return
	 * @throws IOException
	 * @throws RestClientException
	 */
	public boolean verifyCompatible(String name, InputStream fileInputStream) throws IOException, RestClientException {
		byte[] schemaContent = ByteStreams.toByteArray(fileInputStream);
		Parser parser = new Parser();
		org.apache.avro.Schema avroSchema = parser.parse(new String(schemaContent));
		boolean result = avroSchemaRegistry.testCompatibility(name, avroSchema);
		return result;
	}

}
