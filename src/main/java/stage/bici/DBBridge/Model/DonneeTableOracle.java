package stage.bici.DBBridge.Model;

public class DonneeTableOracle {
    String name;
    String oracleType;
    int length;
    int precision;
    int scale;
    boolean nullable;
    public DonneeTableOracle() {
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getOracleType() {
        return oracleType;
    }
    public void setOracleType(String oracleType) {
        this.oracleType = oracleType;
    }
    public int getLength() {
        return length;
    }
    public void setLength(int length) {
        this.length = length;
    }
    public int getPrecision() {
        return precision;
    }
    public void setPrecision(int precision) {
        this.precision = precision;
    }
    public int getScale() {
        return scale;
    }
    public void setScale(int scale) {
        this.scale = scale;
    }
    public boolean isNullable() {
        return nullable;
    }
    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }
}
