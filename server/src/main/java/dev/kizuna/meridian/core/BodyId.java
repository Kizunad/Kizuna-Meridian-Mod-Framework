package dev.kizuna.meridian.core;

/** 稳定的身体实例标识，不承担宿主实体对象的职责。 */
public record BodyId(String value) {
    public BodyId {
        // 宿主决定标识格式，公共层只要求它非空。
        DataChecks.text(value, "身体标识");
    }
}
