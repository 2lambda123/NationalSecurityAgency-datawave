export interface Todo {
  id: number;
  content: string;
}

export interface Meta {
  totalCount: number;
}

export interface GeoFeatures {
  geoByField: GeoByField;
  functions: GeoFunction[];
}

export interface GeoByField {
  [key: string]: GeoTerms; 
}

export interface GeoTerms {
  type: string;
  geo?: Geo;
  geoByTier?: GeoByTier;
}

export interface GeoByTier {
  [key: string]: Geo;
}

export interface Geo {
  wkt: string;
  geoJson: Object;
}

export interface GeoFunction {
  function: string;
  fields: string[];
  geoJson: Object;
}

export interface AppState {
  configPanel: ConfigPanel;
  content: Content;
}

export interface ConfigPanel {
  enabled: boolean;
  selection: string;
}

export interface Content {
  geometry: string;
  editDialog: boolean;
  editDialogText: string;
}

export interface ManualGeometryForm {
  geometry: string,
  geometryType: string,
  createRanges: boolean,
  rangeType: string,
  rangeSettings: QueryRangeSettingsMap
}

export interface QueryRangeSettingsMap {
  [rangeType: string]: QueryRangeSettings;
}

export interface QueryRangeSettings {
  maxEnvelopes: number,
  maxExpansion: number,
  optimizeRanges: boolean,
  rangeSplitThreshold?: number,
  maxRangeOverlap?: number
}

export interface ManualQueryForm {
  query: string,
  fieldTypes: FieldType[]
}

export interface FieldType {
  id: number;
  field: string;
  type: string;
}