package datawave.query.tables.ssdeep;

import datawave.data.normalizer.Normalizer;
import datawave.ingest.csv.config.helper.ExtendedCSVHelper;
import datawave.ingest.data.TypeRegistry;
import datawave.ingest.data.config.CSVHelper;
import datawave.ingest.input.reader.EventRecordReader;
import datawave.ingest.mapreduce.handler.shard.AbstractColumnBasedHandler;
import datawave.ingest.mapreduce.handler.ssdeep.SSDeepIndexHandler;
import datawave.marking.MarkingFunctions;
import datawave.query.testframework.AbstractDataTypeConfig;
import datawave.query.testframework.FieldConfig;
import datawave.query.testframework.RawDataManager;
import datawave.query.testframework.RawMetaData;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Contains all the relevant data needed to configure the SSDeep data type.
 */
public class SSDeepDataType extends AbstractDataTypeConfig {

    private static final Logger log = Logger.getLogger(SSDeepDataType.class);
    private static final Random rVal = new Random(System.currentTimeMillis());

    /**
     * Contains predefined names for the cities datatype. Each enumeration will contain the path of the data ingest file.
     */
    public enum SSDeepEntry {
        // default provided cities with datatype name
        ssdeep("input/ssdeep-data.csv", "ssdeep");
  
        private final String ingestFile;
        private final String datatype;

        SSDeepEntry(final String file, final String name) {
            this.ingestFile = file;
            this.datatype = name;
        }

        public String getIngestFile() {
            return this.ingestFile;
        }

        /**
         * Returns the datatype for the entry.
         *
         * @return datatype for instance
         */
        public String getDataType() {
            return this.datatype;
        }
    }

    /**
     * Defines the data fields for cities datatype.
     */
    @SuppressWarnings("SpellCheckingInspection")
    public enum SSDeepField {
        // order is important, should match the order in the csv files
        PROCESSING_DATE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        EVENT_ID(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        LANGUAGE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        ORIGINAL_SIZE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        PROCESSED_SIZE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        FILE_TYPE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        MD5(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        SHA1(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        SHA256(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        EVENT_DATE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        SECURITY_MARKING(Normalizer.LC_NO_DIACRITICS_NORMALIZER);
        //FILE_DATE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        //FILE_NAME(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        //CHECKSUM_SSDEEP(Normalizer.NOOP_NORMALIZER),
        //IMAGEHEIGHT(Normalizer.NUMBER_NORMALIZER),
        //IMAGEWIDTH(Normalizer.NUMBER_NORMALIZER),
        //PARENT_FILETYPE(Normalizer.LC_NO_DIACRITICS_NORMALIZER),
        //ACCESS_CONTROLS(Normalizer.LC_NO_DIACRITICS_NORMALIZER);

        private static final List<String> Headers;

        static {
            Headers = Stream.of(SSDeepField.values()).map(Enum::name).collect(Collectors.toList());
        }

        /**
         * Retrieves the enumeration that matches the specified field.
         *
         * @param field
         *            string representation of the field.
         * @return enumeration value
         * @throws AssertionError
         *             field does not match any of the enumeration values
         */
        public static SSDeepField getField(final String field) {
            for (final SSDeepField f : SSDeepField.values()) {
                if (f.name().equalsIgnoreCase(field)) {
                    return f;
                }
            }

            throw new AssertionError("invalid SSDeep field(" + field + ")");
        }

        public static List<String> headers() {
            return Headers;
        }

        private static final Map<String, RawMetaData> fieldMetadata;
        static {
            fieldMetadata = new HashMap<>();
            for (SSDeepField field : SSDeepField.values()) {
                fieldMetadata.put(field.name().toLowerCase(), field.metadata);
            }
        }

        /**
         * Returns mapping of ip address fields to the metadata for the field.
         *
         * @return populate map
         */
        public static Map<String,RawMetaData> getFieldsMetadata() {
            return fieldMetadata;
        }

        /**
         * Returns a random set of fields, with or without {@link #EVENT_ID}.
         *
         * @param withEventId
         *            when true, include the event id
         * @return set of random fields
         */
        public static Set<String> getRandomReturnFields(final boolean withEventId) {
            final Set<String> fields = new HashSet<>();
            for (final SSDeepField field : SSDeepField.values()) {
                if (rVal.nextBoolean()) {
                    fields.add(field.name());
                }
            }

            // check to see if event id must be included
            if (withEventId) {
                fields.add(SSDeepField.EVENT_ID.name());
            } else {
                fields.remove(SSDeepField.EVENT_ID.name());
            }

            return fields;
        }

        private static final Map<String,RawMetaData> metadataMapping = new HashMap<>();

        private RawMetaData metadata;

        SSDeepField(final Normalizer<?> normalizer) {
            this(normalizer, false);
        }

        SSDeepField(final Normalizer<?> normalizer, final boolean isMulti) {
            this.metadata = new RawMetaData(this.name(), normalizer, isMulti);
        }

        /**
         * Returns the metadata for this field.
         *
         * @return metadata
         */
        public RawMetaData getMetadata() {
            return metadata;
        }
    }

    // ==================================
    // data manager info
    private static final RawDataManager ssdeepManager = new SSDeepDataManager();

    public static RawDataManager getManager() {
        return ssdeepManager;
    }

    /**
     * Creates a ssdeep datatype entry with all the key/value configuration settings.
     *
     * @param ssdeep
     *            entry for ingest containing datatype and ingest file
     * @param config
     *            hadoop field configuration
     * @throws IOException
     *             unable to load ingest file
     * @throws URISyntaxException
     *             unable to resolve ingest file
     */
    public SSDeepDataType(final SSDeepEntry ssdeep, final FieldConfig config) throws IOException, URISyntaxException {
        this(ssdeep.getDataType(), ssdeep.getIngestFile(), config);
    }

    /**
     * Constructor for ssdeep ingest files that are not defined in the class {@link SSDeepEntry}.
     *
     * @param ssdeep
     *            name of the ssdeep datatype
     * @param ingestFile
     *            ingest file path
     * @param config
     *            hadoop field configuration
     * @throws IOException
     *             error loading test data
     * @throws URISyntaxException
     *             invalid test data file
     */
    public SSDeepDataType(final String ssdeep, final String ingestFile, final FieldConfig config) throws IOException, URISyntaxException {
        super(ssdeep, ingestFile, config, ssdeepManager);
        
        // NOTE: see super for default settings
        // set datatype settings
        //this.hConf.set(this.dataType + "." + SSDeepField.NU.name() + BaseIngestHelper.FIELD_TYPE, NumberType.class.getName());
        this.hConf.set(this.dataType + EventRecordReader.Properties.EVENT_DATE_FIELD_NAME, SSDeepField.PROCESSING_DATE.name());
        this.hConf.set(this.dataType + EventRecordReader.Properties.EVENT_DATE_FIELD_FORMAT, SSDEEP_DATE_FIELD_FORMAT);
        
        this.hConf.set(this.dataType + ExtendedCSVHelper.Properties.EVENT_ID_FIELD_NAME, SSDeepField.EVENT_ID.name());
        
        // fields
        this.hConf.set(this.dataType + CSVHelper.DATA_HEADER, String.join(",", SSDeepField.headers()));
        this.hConf.set(this.dataType + CSVHelper.PROCESS_EXTRA_FIELDS, "true");

        // ssdeep index handler
        this.hConf.set(this.dataType + TypeRegistry.HANDLER_CLASSES, String.join(",", AbstractColumnBasedHandler.class.getName(), SSDeepIndexHandler.class.getName()));
        this.hConf.set(this.dataType + SSDeepIndexHandler.SSDEEP_FIELD_SET, "CHECKSUM_SSDEEP");

        this.hConf.set(SSDeepIndexHandler.SSDEEP_INDEX_TABLE_NAME, SSDeepQueryTestTableHelper.SSDEEP_INDEX_TABLE_NAME);
        log.debug(this.toString());
    }

    private static final String SSDEEP_DATE_FIELD_FORMAT = "yyyy-MM-dd hh:mm:ss";

    private static final String[] AUTH_VALUES = new String[] {"A","B","C","D","E","F","G","H"};
    private static final Authorizations TEST_AUTHS = new Authorizations(AUTH_VALUES);
    private static final Authorizations EXPANSION_AUTHS = new Authorizations("ct-a", "b-ct", "not-b-ct");
    
    public static Authorizations getTestAuths() {
        return TEST_AUTHS;
    }
    
    public static Authorizations getExpansionAuths() {
        return EXPANSION_AUTHS;
    }
    
    @Override
    public String getSecurityMarkingFieldNames() {
        // TODO: fix markings
        return "";
    }
    
    @Override
    public String getSecurityMarkingFieldDomains() {
        return MarkingFunctions.Default.COLUMN_VISIBILITY;
    }
    
    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{" + super.toString() + "}";
    }
}
