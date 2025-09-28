package stage.bici.DBBridge.Model;

public class Oracle {
    String host;
    String port;
    String serviceName;
    String username;
    String driver = "oracle.jdbc.OracleDriver";
    String sid;
    String password;
    public Oracle() {
    }
    public String getSid() {
        return sid;
    }
    public void setSid(String sid) {
        this.sid = sid;
    }
    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }
    public String getHost() {
        return host;
    }
    public void setHost(String host) {
        this.host = host;
    }
    public String getPort() {
        return port;
    }
    public void setPort(String port) {
        this.port = port;
    }
    public String getServiceName() {
        return serviceName;
    }
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }
    public String getUsername() {
        return username;
    }
    public void setUsername(String username) {
        this.username = username;
    }
    public String getDriver() {
        return driver;
    }
    public void setDriver(String driver) {
        this.driver = driver;
    }
    // Méthode utilitaire pour construire l'URL
    public String buildConnectionUrl() {
        if (serviceName != null && !serviceName.isEmpty()) {
            // Format avec SERVICE_NAME
            return String.format("jdbc:oracle:thin:@//%s:%s/%s", host, port, serviceName);
        } else if (sid != null && !sid.isEmpty()) {
            // Format avec SID
            return String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, sid);
        } else {
            throw new IllegalArgumentException("Ni serviceName ni SID n'ont été fournis !");
        }
    }
}
