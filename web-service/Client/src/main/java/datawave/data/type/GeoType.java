package datawave.data.type;

import datawave.data.normalizer.Normalizer;

public class GeoType extends BaseType<String> {
    
    private static final long serialVersionUID = 8429780512238258642L;
    
    public GeoType() {
        super(Normalizer.GEO_NORMALIZER);
    }
    
}
