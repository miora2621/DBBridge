package stage.bici.DBBridge.Model;

public class MigrationScript {
    private String objectName;
    private ScriptType type;
    private String oracleScript;
    private String postgresScript;
    private boolean success;
    private String errorMessage;
    
    public enum ScriptType {
        SEQUENCE,
        TABLE,
        DATA,
        INDEX,
        FOREIGN_KEY,
        UNIQUE_CONSTRAINT,
        VIEW,
        FUNCTION,
        TRIGGER
    }
    
    public MigrationScript(String objectName, ScriptType type, String oracleScript, String postgresScript) {
        this.objectName = objectName;
        this.type = type;
        this.oracleScript = oracleScript;
        this.postgresScript = postgresScript;
        this.success = true;
        this.errorMessage = null;
    }
    
    public MigrationScript(String objectName, ScriptType type, String oracleScript, String postgresScript, 
                          boolean success, String errorMessage) {
        this.objectName = objectName;
        this.type = type;
        this.oracleScript = oracleScript;
        this.postgresScript = postgresScript;
        this.success = success;
        this.errorMessage = errorMessage;
    }
    
    // Getters
    public String getObjectName() {
        return objectName;
    }
    
    public ScriptType getType() {
        return type;
    }
    
    public String getOracleScript() {
        return oracleScript;
    }
    
    public String getPostgresScript() {
        return postgresScript;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    // Setters
    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }
    
    public void setType(ScriptType type) {
        this.type = type;
    }
    
    public void setOracleScript(String oracleScript) {
        this.oracleScript = oracleScript;
    }
    
    public void setPostgresScript(String postgresScript) {
        this.postgresScript = postgresScript;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(type).append(": ").append(objectName).append(" ===\n");
        sb.append("Status: ").append(success ? "✅ SUCCESS" : "❌ FAILED").append("\n");
        if (errorMessage != null) {
            sb.append("Error: ").append(errorMessage).append("\n");
        }
        sb.append("\nOracle Script:\n").append(oracleScript).append("\n");
        sb.append("\nPostgreSQL Script:\n").append(postgresScript).append("\n");
        return sb.toString();
    }
}