package io.crate.metadata.doc;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.crate.Constants;
import io.crate.analyze.AnalyzedTableElements;
import io.crate.analyze.Analyzer;
import io.crate.analyze.CreateTableAnalysis;
import io.crate.analyze.CreateTableStatementAnalyzer;
import io.crate.metadata.*;
import io.crate.metadata.table.SchemaInfo;
import io.crate.sql.parser.SqlParser;
import io.crate.sql.tree.Statement;
import io.crate.types.ArrayType;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.GeoPointType;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.TransportPutIndexTemplateAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class DocIndexMetaDataTest {


    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    static {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
    }

    private IndexMetaData getIndexMetaData(String indexName, XContentBuilder builder) throws IOException {
        return getIndexMetaData(indexName, builder, ImmutableSettings.Builder.EMPTY_SETTINGS, null);
    }

    private IndexMetaData getIndexMetaData(String indexName,
                                           XContentBuilder builder,
                                           Settings settings,
                                           @Nullable AliasMetaData aliasMetaData) throws IOException {
        byte[] data = builder.bytes().toBytes();
        Map<String, Object> mappingSource = XContentHelper.convertToMap(data, true).v2();
        mappingSource = sortProperties(mappingSource);

        ImmutableSettings.Builder settingsBuilder = ImmutableSettings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put(settings);

        IndexMetaData.Builder mdBuilder = IndexMetaData.builder(indexName)
                .settings(settingsBuilder)
                .putMapping(new MappingMetaData(Constants.DEFAULT_MAPPING_TYPE, mappingSource));
        if (aliasMetaData != null) {
            mdBuilder.putAlias(aliasMetaData);
        }
        return mdBuilder.build();
    }

    private DocIndexMetaData newMeta(IndexMetaData metaData, String name) throws IOException {
        return new DocIndexMetaData(metaData, new TableIdent(null, name)).build();
    }

    @Test
    public void testNestedColumnIdent() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("properties")
                    .startObject("person")
                        .startObject("properties")
                            .startObject("addresses")
                                .startObject("properties")
                                    .startObject("city")
                                        .field("type", "string")
                                        .field("index", "not_analyzed")
                                    .endObject()
                                    .startObject("country")
                                        .field("type", "string")
                                        .field("index", "not_analyzed")
                                    .endObject()
                                .endObject()
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject()
                .endObject();

        IndexMetaData metaData = getIndexMetaData("test1", builder);
        DocIndexMetaData md = newMeta(metaData, "test1");

        ReferenceInfo referenceInfo = md.references().get(new ColumnIdent("person", Arrays.asList("addresses", "city")));
        assertNotNull(referenceInfo);
    }

    @Test
    public void testExtractObjectColumnDefinitions() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("properties")
                .startObject("implicit_dynamic")
                    .startObject("properties")
                        .startObject("name")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                        .endObject()
                    .endObject()
                .endObject()
                .startObject("explicit_dynamic")
                    .field("dynamic", "true")
                    .startObject("properties")
                        .startObject("name")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                        .endObject()
                        .startObject("age")
                            .field("type", "integer")
                            .field("index", "not_analyzed")
                        .endObject()
                    .endObject()
                .endObject()
                .startObject("ignored")
                    .field("dynamic", "false")
                    .startObject("properties")
                        .startObject("name")
                            .field("type", "string")
                            .field("index", "not_analyzed")
                        .endObject()
                        .startObject("age")
                            .field("type", "integer")
                            .field("index", "not_analyzed")
                        .endObject()
                    .endObject()
                .endObject()
                .startObject("strict")
                    .field("dynamic", "strict")
                    .startObject("properties")
                        .startObject("age")
                            .field("type", "integer")
                            .field("index", "not_analyzed")
                        .endObject()
                    .endObject()
                .endObject()
                .endObject()
                .endObject();
        IndexMetaData metaData = getIndexMetaData("test1", builder);
        DocIndexMetaData md = newMeta(metaData, "test1");
        assertThat(md.columns().size(), is(4));
        assertThat(md.references().size(), is(16));
        assertThat(md.references().get(new ColumnIdent("implicit_dynamic")).objectType(), is(ReferenceInfo.ObjectType.DYNAMIC));
        assertThat(md.references().get(new ColumnIdent("explicit_dynamic")).objectType(), is(ReferenceInfo.ObjectType.DYNAMIC));
        assertThat(md.references().get(new ColumnIdent("ignored")).objectType(), is(ReferenceInfo.ObjectType.IGNORED));
        assertThat(md.references().get(new ColumnIdent("strict")).objectType(), is(ReferenceInfo.ObjectType.STRICT));
    }

    @Test
    public void testExtractColumnDefinitions() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("_meta")
                .field("primary_keys", "id")
                .endObject()
                .startObject("properties")
                .startObject("id")
                .field("type", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("title")
                .field("type", "string")
                .field("index", "no")
                .endObject()
                .startObject("datum")
                .field("type", "date")
                .endObject()
                .startObject("content")
                .field("type", "string")
                .field("index", "analyzed")
                .field("analyzer", "standard")
                .endObject()
                .startObject("person")
                .startObject("properties")
                .startObject("first_name")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("birthday")
                .field("type", "date")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .startObject("nested")
                .field("type", "nested")
                .startObject("properties")
                .startObject("inner_nested")
                .field("type", "date")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject();


        IndexMetaData metaData = getIndexMetaData("test1", builder);
        DocIndexMetaData md = newMeta(metaData, "test1");

        assertEquals(6, md.columns().size());
        assertEquals(15, md.references().size());

        ImmutableList<ReferenceInfo> columns = ImmutableList.copyOf(md.columns());

        assertThat(columns.get(0).ident().columnIdent().name(), is("content"));
        assertEquals(DataTypes.STRING, columns.get(0).type());
        assertEquals(ReferenceInfo.IndexType.ANALYZED, columns.get(0).indexType());
        assertThat(columns.get(0).ident().tableIdent().name(), is("test1"));

        ImmutableList<ReferenceInfo> references = ImmutableList.<ReferenceInfo>copyOf(md.references().values());


        ReferenceInfo birthday = md.references().get(new ColumnIdent("person", "birthday"));
        assertEquals(DataTypes.TIMESTAMP, birthday.type());
        assertEquals(ReferenceInfo.IndexType.NOT_ANALYZED, birthday.indexType());

        ReferenceInfo title = md.references().get(new ColumnIdent("title"));
        assertEquals(ReferenceInfo.IndexType.NO, title.indexType());

        List<String> fqns = Lists.transform(references, new Function<ReferenceInfo, String>() {
            @Nullable
            @Override
            public String apply(@Nullable ReferenceInfo input) {
                return input.ident().columnIdent().fqn();
            }
        });

        assertThat(fqns, Matchers.<List<String>>is(
                ImmutableList.of("_doc", "_id", "_raw", "_score", "_uid", "_version", "content", "datum", "id", "nested", "nested.inner_nested",
                        "person", "person.birthday", "person.first_name", "title")));

    }

    @Test
    public void testExtractPartitionedByColumns() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("_meta")
                .field("primary_keys", "id")
                .startArray("partitioned_by")
                    .startArray()
                        .value("datum").value("date")
                    .endArray()
                .endArray()
                .endObject()
                .startObject("properties")
                .startObject("id")
                .field("type", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("title")
                .field("type", "string")
                .field("index", "no")
                .endObject()
                .startObject("content")
                .field("type", "string")
                .field("index", "analyzed")
                .field("analyzer", "standard")
                .endObject()
                .startObject("person")
                .startObject("properties")
                .startObject("first_name")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("birthday")
                .field("type", "date")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .startObject("nested")
                .field("type", "nested")
                .startObject("properties")
                .startObject("inner_nested")
                .field("type", "date")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        IndexMetaData metaData = getIndexMetaData("test1", builder);
        DocIndexMetaData md = newMeta(metaData, "test1");

        assertEquals(6, md.columns().size());
        assertEquals(15, md.references().size());
        assertEquals(1, md.partitionedByColumns().size());
        assertEquals(DataTypes.TIMESTAMP, md.partitionedByColumns().get(0).type());
        assertThat(md.partitionedByColumns().get(0).ident().columnIdent().fqn(), is("datum"));
    }

    private Map<String, Object> sortProperties(Map<String, Object> mappingSource) {
        return sortProperties(mappingSource, false);
    }

    /**
     * in the DocumentMapper that ES uses at some place the properties of the mapping are sorted.
     * this logic doesn't seem to be triggered if the IndexMetaData is created using the
     * IndexMetaData.Builder.
     * <p/>
     * in order to have the same behaviour as if a Node was started and a index with mapping was created
     * using the ES tools pre-sort the mapping here.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sortProperties(Map<String, Object> mappingSource, boolean doSort) {
        Map<String, Object> map;
        if (doSort) {
            map = new TreeMap<>();
        } else {
            map = new HashMap<>();
        }

        boolean sortNext;
        Object value;
        for (Map.Entry<String, Object> entry : mappingSource.entrySet()) {
            value = entry.getValue();
            sortNext = entry.getKey().equals("properties");

            if (value instanceof Map) {
                map.put(entry.getKey(), sortProperties((Map) entry.getValue(), sortNext));
            } else {
                map.put(entry.getKey(), entry.getValue());
            }
        }

        return map;
    }

    @Test
    public void testExtractColumnDefinitionsFromEmptyIndex() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .endObject()
                .endObject();
        IndexMetaData metaData = getIndexMetaData("test2", builder);
        DocIndexMetaData md = newMeta(metaData, "test2");
        assertThat(md.columns(), hasSize(0));
    }

    @Test
    public void testDocSysColumnReferences() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("properties")
                .startObject("content")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        DocIndexMetaData metaData = newMeta(getIndexMetaData("test", builder), "test");
        ReferenceInfo id = metaData.references().get(new ColumnIdent("_id"));
        assertNotNull(id);

        ReferenceInfo version = metaData.references().get(new ColumnIdent("_version"));
        assertNotNull(version);

        ReferenceInfo score = metaData.references().get(new ColumnIdent("_score"));
        assertNotNull(score);
    }

    @Test
    public void testExtractPrimaryKey() throws Exception {

        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("_meta")
                .field("primary_keys", "id")
                .endObject()
                .startObject("properties")
                .startObject("id")
                .field("type", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("title")
                .field("type", "string")
                .field("index", "no")
                .endObject()
                .startObject("datum")
                .field("type", "date")
                .endObject()
                .startObject("content")
                .field("type", "string")
                .field("index", "analyzed")
                .field("analyzer", "standard")
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        IndexMetaData metaData = getIndexMetaData("test3", builder);
        DocIndexMetaData md = newMeta(metaData, "test3");


        assertThat(md.primaryKey().size(), is(1));
        assertThat(md.primaryKey(), contains(new ColumnIdent("id")));

        builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("properties")
                .startObject("content")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        md = newMeta(getIndexMetaData("test4", builder), "test4");
        assertThat(md.primaryKey().size(), is(1)); // _id is always the fallback primary key

        builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .endObject()
                .endObject();
        md = newMeta(getIndexMetaData("test5", builder), "test5");
        assertThat(md.primaryKey().size(), is(1));
    }

    @Test
    public void extractRoutingColumn() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("_meta")
                .field("primary_keys", "id")
                .endObject()
                .startObject("properties")
                .startObject("id")
                .field("type", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("title")
                .field("type", "multi_field")
                .field("path", "just_name")
                .startObject("fields")
                .startObject("title")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("ft")
                .field("type", "string")
                .field("index", "analyzed")
                .field("analyzer", "english")
                .endObject()
                .endObject()
                .endObject()
                .startObject("datum")
                .field("type", "date")
                .endObject()
                .startObject("content")
                .field("type", "multi_field")
                .field("path", "just_name")
                .startObject("fields")
                .startObject("content")
                .field("type", "string")
                .field("index", "no")
                .endObject()
                .startObject("ft")
                .field("type", "string")
                .field("index", "analyzed")
                .field("analyzer", "english")
                .endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        DocIndexMetaData md = newMeta(getIndexMetaData("test8", builder), "test8");
        assertThat(md.routingCol(), is(new ColumnIdent("id")));

        builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("properties")
                .startObject("content")
                .field("type", "string")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        md = newMeta(getIndexMetaData("test9", builder), "test8");
        assertThat(md.routingCol(), is(new ColumnIdent("_id")));

        builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("_meta")
                .field("primary_keys", "id")
                .endObject()
                .startObject("_routing")
                .field("path", "id")
                .endObject()
                .startObject("properties")
                .startObject("id")
                .field("type", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .startObject("content")
                .field("type", "string")
                .field("index", "no")
                .endObject()
                .endObject()
                .endObject()
                .endObject();

        md = newMeta(getIndexMetaData("test10", builder), "test10");
        assertThat(md.routingCol(), is(new ColumnIdent("id")));
    }

    @Test
    public void extractRoutingColumnFromEmptyIndex() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .endObject()
                .endObject();
        DocIndexMetaData md = newMeta(getIndexMetaData("test11", builder), "test11");
        assertThat(md.routingCol(), is(new ColumnIdent("_id")));
    }

    @Test
    public void testAutogeneratedPrimaryKey() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .endObject()
                .endObject();
        DocIndexMetaData md = newMeta(getIndexMetaData("test11", builder), "test11");
        assertThat(md.primaryKey().size(), is(1));
        assertThat(md.primaryKey().get(0), is(new ColumnIdent("_id")));
        assertThat(md.hasAutoGeneratedPrimaryKey(), is(true));
    }

    @Test
    public void testNoAutogeneratedPrimaryKey() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject(Constants.DEFAULT_MAPPING_TYPE)
                .startObject("_meta")
                .field("primary_keys", "id")
                .endObject()
                .startObject("properties")
                .startObject("field")
                .field("id", "integer")
                .field("index", "not_analyzed")
                .endObject()
                .endObject()
                .endObject();
        DocIndexMetaData md = newMeta(getIndexMetaData("test11", builder), "test11");
        assertThat(md.primaryKey().size(), is(1));
        assertThat(md.primaryKey().get(0), is(new ColumnIdent("id")));
        assertThat(md.hasAutoGeneratedPrimaryKey(), is(false));
    }

    @Test
    public void testGeoPointType() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement("create table foo (p geo_point)");
        assertThat(md.columns().size(), is(1));
        ReferenceInfo referenceInfo = md.columns().get(0);
        assertThat((GeoPointType) referenceInfo.type(), equalTo(DataTypes.GEO_POINT));
    }

    @Test
    public void testCreateTableMappingGenerationAndParsingCompat() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement("create table foo (" +
                    "id int primary key," +
                    "tags array(string)," +
                    "o object as (" +
                    "   age int," +
                    "   name string" +
                    ")," +
                    "date timestamp primary key" +
                ") partitioned by (date)");

        assertThat(md.columns().size(), is(4));
        assertThat(md.primaryKey(), Matchers.contains(new ColumnIdent("id"), new ColumnIdent("date")));
        assertTrue(md.references().get(new ColumnIdent("tags")).type().equals(new ArrayType(DataTypes.STRING)));
    }

    @Test
    public void testCreateTableMappingGenerationAndParsingArrayInsideObject() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement(
                "create table t1 (" +
                        "id int primary key," +
                        "details object as (names array(string))" +
                        ") with (number_of_replicas=0)");
        DataType type = md.references().get(new ColumnIdent("details", "names")).type();
        assertThat(type, Matchers.<DataType>equalTo(new ArrayType(DataTypes.STRING)));
    }

    @Test
    public void testCreateTableMappingGenerationAndParsingCompatNoMeta() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement("create table foo (id int, name string)");
        assertThat(md.columns().size(), is(2));
        assertThat(md.hasAutoGeneratedPrimaryKey(), is(true));
    }

    private DocIndexMetaData getDocIndexMetaDataFromStatement(String stmt) throws IOException {
        Statement statement = SqlParser.createStatement(stmt);
        CreateTableStatementAnalyzer analyzer = new CreateTableStatementAnalyzer();
        ClusterService clusterService = mock(ClusterService.class);
        CreateTableAnalysis analysis = new CreateTableAnalysis(
                new ReferenceInfos(
                        ImmutableMap.<String, SchemaInfo>of("doc", new DocSchemaInfo(clusterService, mock(TransportPutIndexTemplateAction.class)))),
                new FulltextAnalyzerResolver(clusterService, mock(IndicesAnalysisService.class)),
                new Analyzer.ParameterContext(new Object[0], new Object[0][]));
        analysis.analyzedTableElements(new AnalyzedTableElements());

        ImmutableSettings.Builder settingsBuilder = ImmutableSettings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put(analysis.indexSettings());

        analyzer.process(statement, analysis);
        IndexMetaData indexMetaData = IndexMetaData.builder(analysis.tableIdent().name())
                .settings(settingsBuilder)
                .putMapping(new MappingMetaData(Constants.DEFAULT_MAPPING_TYPE, analysis.mapping()))
                .build();

        return newMeta(indexMetaData, analysis.tableIdent().name());
    }

    @Test
    public void testTemplateUpdate() throws Exception {
        // regression test: alias must be set in the updated template

        Settings settings = ImmutableSettings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .build();

        IndexMetaData md1 = IndexMetaData.builder("t1")
                .settings(settings)
                .build();
        DocIndexMetaData docIndexMd1 = new DocIndexMetaData(md1, new TableIdent("doc", "t1")).build();

        XContentBuilder builder = XContentFactory.jsonBuilder()
                .startObject()
                .startObject("properties")
                    .startObject("id")
                        .field("type", "integer")
                    .endObject()
                .endObject()
                .endObject();
        DocIndexMetaData docIndexMd2 = newMeta(getIndexMetaData(
                "t2",  builder, ImmutableSettings.EMPTY, AliasMetaData.builder("tables").build()), "t2");

        TransportPutIndexTemplateAction transportPutIndexTemplateAction = mock(TransportPutIndexTemplateAction.class);
        ArgumentCaptor<PutIndexTemplateRequest> argumentCaptor = ArgumentCaptor.forClass(PutIndexTemplateRequest.class);
        docIndexMd1.merge(docIndexMd2, transportPutIndexTemplateAction, true);
        verify(transportPutIndexTemplateAction).execute(argumentCaptor.capture());

        PutIndexTemplateRequest request = argumentCaptor.getValue();
        Field aliasesField = PutIndexTemplateRequest.class.getDeclaredField("aliases");
        aliasesField.setAccessible(true);
        Set aliases = (Set)aliasesField.get(request);

        assertThat(aliases.size(), is(1));
    }

    @Test
    public void testCompoundIndexColumn() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement("create table t (" +
                "  id integer primary key," +
                "  name string," +
                "  fun string index off," +
                "  INDEX fun_name_ft using fulltext(name, fun)" +
                ")");
        assertThat(md.indices().size(), is(1));
        assertThat(md.columns().size(), is(3));
        assertThat(md.indices().get(ColumnIdent.fromPath("fun_name_ft")), instanceOf(IndexReferenceInfo.class));
        IndexReferenceInfo indexInfo = md.indices().get(ColumnIdent.fromPath("fun_name_ft"));
        assertThat(indexInfo.analyzer(), is("standard"));
        assertThat(indexInfo.columns().size(), is(2));
        assertThat(indexInfo.columns(), hasItem(md.references().get(new ColumnIdent("name"))));
        assertThat(indexInfo.columns(), hasItem(md.references().get(new ColumnIdent("fun"))));
        assertThat(indexInfo.indexType(), is(ReferenceInfo.IndexType.ANALYZED));
        assertThat(indexInfo.ident().columnIdent().fqn(), is("fun_name_ft"));
    }

    @Test
    public void testCompoundIndexColumnNested() throws Exception {
        DocIndexMetaData md = getDocIndexMetaDataFromStatement("create table t (" +
                "  id integer primary key," +
                "  name string," +
                "  o object as (" +
                "    fun string" +
                "  )," +
                "  INDEX fun_name_ft using fulltext(name, o['fun'])" +
                ")");
        assertThat(md.indices().size(), is(1));
        assertThat(md.columns().size(), is(3));
        assertThat(md.indices().get(ColumnIdent.fromPath("fun_name_ft")), instanceOf(IndexReferenceInfo.class));
        IndexReferenceInfo indexInfo = md.indices().get(ColumnIdent.fromPath("fun_name_ft"));
        assertThat(indexInfo.analyzer(), is("standard"));
        assertThat(indexInfo.columns().size(), is(2));
        assertThat(indexInfo.columns(), hasItem(md.references().get(new ColumnIdent("name"))));
        assertThat(indexInfo.columns(), hasItem(md.references().get(ColumnIdent.fromPath("o.fun"))));
        assertThat(indexInfo.indexType(), is(ReferenceInfo.IndexType.ANALYZED));
        assertThat(indexInfo.ident().columnIdent().fqn(), is("fun_name_ft"));
    }
}



