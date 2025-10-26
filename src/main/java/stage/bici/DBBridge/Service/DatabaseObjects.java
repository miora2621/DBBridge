package stage.bici.DBBridge.Service;

import java.util.*;

public class DatabaseObjects {
    public String owner;
    public Set<String> validTables = new HashSet<>();
    public Set<String> validSequences = new HashSet<>();
    public Set<String> validViews = new HashSet<>();
    public Set<String> validFunctions = new HashSet<>();
    public Set<String> validTriggers = new HashSet<>();
    public Map<String, Boolean> pkEnabledByTable = new HashMap<>();
    public Map<String, String> viewDefinitions = new HashMap<>();
    public Map<String, Set<String>> viewDependencies = new HashMap<>();
    public Map<String, Set<String>> missingDependencies = new HashMap<>();
    public InvalidObjectsStats invalidStats;
}