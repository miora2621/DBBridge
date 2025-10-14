package stage.bici.DBBridge.Model;

public class SequenceInfo {
    private String name;
    private long lastValue;
    private long incrementBy;
    private long minValue;
    private long maxValue;
    private boolean cycle;
    private long cacheSize;
    private long lastNumber;
    private boolean ordered;
    
   
    public boolean isOrdered() {
        return ordered;
    }
    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }
    public long getLastNumber() {
        return lastNumber;
    }
    public void setLastNumber(long lastNumber) {
        this.lastNumber = lastNumber;
    }
    // Getters et Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public long getLastValue() { return lastValue; }
    public void setLastValue(long lastValue) { this.lastValue = lastValue; }
    
    public long getIncrementBy() { return incrementBy; }
    public void setIncrementBy(long incrementBy) { this.incrementBy = incrementBy; }
    
    public long getMinValue() { return minValue; }
    public void setMinValue(long minValue) { this.minValue = minValue; }
    
    public long getMaxValue() { return maxValue; }
    public void setMaxValue(long maxValue) { this.maxValue = maxValue; }
    
    public boolean isCycle() { return cycle; }
    public void setCycle(boolean cycle) { this.cycle = cycle; }
    
    public long getCacheSize() { return cacheSize; }
    public void setCacheSize(long cacheSize) { this.cacheSize = cacheSize; }
}
