package stage.bici.DBBridge.Model;

public class DonneeTablePostgres {
    String name;
    String pgType;
    int length;
    int precision;
    int scale;
    boolean nullable;
    public DonneeTablePostgres() {
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getPgType() {
        return pgType;
    }
    public void setPgType(String pgType) {
        this.pgType = pgType;
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
