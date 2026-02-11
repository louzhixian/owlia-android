package app.botdrop.ops;

public class RuleSource {

    public final RuleSourceType sourceType;
    public final String sourceVersion;
    public final String sourceId;

    public RuleSource(RuleSourceType sourceType, String sourceVersion, String sourceId) {
        this.sourceType = sourceType;
        this.sourceVersion = sourceVersion;
        this.sourceId = sourceId;
    }
}
