package datawave.data.normalizer;

import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.io.WKTReader;
import mil.nga.giat.geowave.core.geotime.GeometryUtils;
import mil.nga.giat.geowave.core.geotime.index.dimension.LatitudeDefinition;
import mil.nga.giat.geowave.core.geotime.index.dimension.LongitudeDefinition;
import mil.nga.giat.geowave.core.geotime.ingest.SpatialDimensionalityTypeProvider.SpatialIndexBuilder;
import mil.nga.giat.geowave.core.index.ByteArrayId;
import mil.nga.giat.geowave.core.index.NumericIndexStrategy;
import mil.nga.giat.geowave.core.index.dimension.NumericDimensionDefinition;
import mil.nga.giat.geowave.core.index.sfc.SFCFactory;
import mil.nga.giat.geowave.core.index.sfc.data.MultiDimensionalNumericData;
import mil.nga.giat.geowave.core.index.sfc.tiered.TieredSFCIndexFactory;
import mil.nga.giat.geowave.core.store.index.BasicIndexModel;
import mil.nga.giat.geowave.core.store.index.CustomIdIndex;
import mil.nga.giat.geowave.core.store.index.PrimaryIndex;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A normalizer that, given a Well-Known Text string representing an arbitrary geometry, will perform GeoWave indexing with a defauls spatial geowave index
 * configuration
 *
 */
public class GeometryNormalizer implements Normalizer<datawave.data.type.util.Geometry>, OneToManyNormalizer<datawave.data.type.util.Geometry> {
    private static final long serialVersionUID = 171360806347433135L;
    
    private static final int LONGITUDE_BITS = 31;
    private static final int LATITUDE_BITS = 31;
    
    // @formatter:off
    public static NumericIndexStrategy indexStrategy = TieredSFCIndexFactory.createFullIncrementalTieredStrategy(
            new NumericDimensionDefinition[]{
                    new LongitudeDefinition(),
                    new LatitudeDefinition(
                            true)
                    // just use the same range for latitude to make square sfc values in
                    // decimal degrees (EPSG:4326)
            },
            new int[]{
                    LONGITUDE_BITS,
                    LATITUDE_BITS
            },
            SFCFactory.SFCType.HILBERT);
    // @formatter:on    
    
    private static final String[] geomTypes = new String[] {"GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON",
            "GEOMETRYCOLLECTION", "CIRCULARSTRING", "COMPOUNDCURVE", "CURVEPOLYGON", "MULTICURVE", "MULTISURFACE", "CURVE", "SURFACE", "POLYHEDRALSURFACE",
            "TIN", "TRIANGLE"};
    private static final String[] zGeomTypes = new String[geomTypes.length];
    
    static {
        for (int i = 0; i < geomTypes.length; i++)
            zGeomTypes[i] = geomTypes[i] + " Z";
    }
    
    /**
     * Expects to receive an Open Geospatial Consortium compliant Well-Known test string An example for points is of the form:
     *
     * POINT ([number][space][number])
     */
    @Override
    public String normalize(String wellKnownText) throws IllegalArgumentException {
        return normalizeDelegateType(new datawave.data.type.util.Geometry(getGeometryFromWKT(wellKnownText)));
    }
    
    @Override
    public List<String> normalizeToMany(String wellKnownText) throws IllegalArgumentException {
        return normalizeDelegateTypeToMany(new datawave.data.type.util.Geometry(getGeometryFromWKT(wellKnownText)));
    }
    
    /**
     * We cannot support regex against numbers
     */
    @Override
    public String normalizeRegex(String fieldRegex) throws IllegalArgumentException {
        throw new IllegalArgumentException("Cannot normalize a regex against a geometry field");
    }
    
    @Override
    public List<String> normalizeDelegateTypeToMany(datawave.data.type.util.Geometry geometry) {
        List<String> list = Lists.newArrayList();
        for (ByteArrayId one : getIndicesFromGeometry((geometry.getJTSGeometry()))) {
            list.add(getEncodedStringFromIndexBytes(one));
        }
        return list;
    }
    
    public String normalizeDelegateType(datawave.data.type.util.Geometry geometry) {
        return getEncodedStringFromIndexBytes(getSingleIndexFromGeometry((geometry.getJTSGeometry())));
    }
    
    public static List<String> getEncodedStringsFromGeometry(Geometry geometry) {
        List<ByteArrayId> indices = getIndicesFromGeometry(geometry);
        List<String> retVal = new ArrayList<String>(indices.size());
        for (ByteArrayId index : indices) {
            retVal.add(getEncodedStringFromIndexBytes(index));
        }
        return retVal;
    }
    
    public static String getEncodedStringFromIndexBytes(ByteArrayId index) {
        return Hex.encodeHexString(index.getBytes());
    }
    
    public static Geometry getGeometryFromWKT(String wellKnownText) throws IllegalArgumentException {
        try {
            return new WKTReader().read(StringUtils.replaceEach(wellKnownText, zGeomTypes, geomTypes));
        } catch (com.vividsolutions.jts.io.ParseException e) {
            throw new IllegalArgumentException("Cannot parse well-known text", e);
        }
    }
    
    private static ByteArrayId getSingleIndexFromGeometry(Geometry geometry) {
        final List<ByteArrayId> insertionIds = new ArrayList<ByteArrayId>();
        for (MultiDimensionalNumericData range : GeometryUtils.basicConstraintsFromGeometry(geometry).getIndexConstraints(indexStrategy)) {
            insertionIds.addAll(indexStrategy.getInsertionIds(range, 1));
        }
        if (insertionIds.size() == 1) {
            return insertionIds.get(0);
        }
        // this should never occur
        throw new IllegalArgumentException("Cannot normalize input geometry, no resulting indices");
    }
    
    private static List<ByteArrayId> getIndicesFromGeometry(Geometry geometry) {
        final List<ByteArrayId> insertionIds = new ArrayList<ByteArrayId>();
        for (MultiDimensionalNumericData range : GeometryUtils.basicConstraintsFromGeometry(geometry).getIndexConstraints(indexStrategy)) {
            insertionIds.addAll(indexStrategy.getInsertionIds(range));
        }
        return insertionIds;
    }
    
    @Override
    public datawave.data.type.util.Geometry denormalize(String wellKnownText) {
        // this is assuming the input string is not actually normalized
        // (which oddly is the case with other normalizers)
        return new datawave.data.type.util.Geometry(getGeometryFromWKT(wellKnownText));
    }
    
    @Override
    public Collection<String> expand(String wellKnownText) {
        List<ByteArrayId> indices = getIndicesFromGeometry(getGeometryFromWKT(wellKnownText));
        List<String> retVal = new ArrayList<String>(indices.size());
        for (ByteArrayId index : indices) {
            retVal.add(getEncodedStringFromIndexBytes(index));
        }
        return retVal;
    }
}
