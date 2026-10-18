package dev.kizuna.meridian.core;

import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.error.MeridianErrors.Data;

/** 真元的唯一可计量单位；属性和相态只是账本维度。 */
public record Qi(QiPhase phase, QiAttribute attribute, double amount) {
    public Qi {
        // 数量与类型必须同时明确，零数量可用于算术结果。
        MeridianErrors.required(phase, Data.QI_PHASE_REQUIRED);
        MeridianErrors.required(attribute, Data.QI_ATTRIBUTE_REQUIRED);
        DataChecks.nonNegative(amount, "真元数量");
    }

    public static Qi empty(QiPhase phase, QiAttribute attribute) {
        // 保留类型的零值用于计算；账户存储空余额时使用空明细列表。
        return new Qi(phase, attribute, 0);
    }

    public Qi add(Qi other) {
        // 加法只合并同类数量，不隐式进行相变或属性转化。
        requireSameKind(other);
        return new Qi(phase, attribute, amount + other.amount);
    }

    public Qi subtract(Qi other) {
        // 扣减先验证同类和余额；机器舍入产生的极小负尾差归零。
        requireSameKind(other);
        DataChecks.atMost(other.amount, amount, "真元扣减");
        return new Qi(phase, attribute, Math.max(0, amount - other.amount));
    }

    private void requireSameKind(Qi other) {
        // 属性按内容 ID 的值比较，不依赖两个属性对象是否为同一实例。
        MeridianErrors.required(other, Data.QI_REQUIRED);
        if (phase != other.phase || !attribute.equals(other.attribute)) {
            throw MeridianErrors.invalid(Data.QI_KIND_MISMATCH);
        }
    }

    public enum QiPhase {
        /** 游离态，游炁境。 */
        FREE,
        /** 气态，真炁境。 */
        GAS,
        /** 液态，化元境。 */
        LIQUID,
        /** 固态，凝元境。 */
        SOLID,
        /** 有序态，天纹境：真元排列成与天地纹理同构的纹。 */
        ORDERED,
        /** 相干态，化虚境：高密度天纹耦合干涉。 */
        COHERENT
    }

    /** 属性以命名空间区分；第三方熔岩等属性不会被合并成同一个 CUSTOM。 */
    public record QiAttribute(DefinitionId id) {
        public static final QiAttribute NONE = of("meridian:none");
        public static final QiAttribute FIRE = of("meridian:fire");
        public static final QiAttribute WATER = of("meridian:water");
        public static final QiAttribute WOOD = of("meridian:wood");
        public static final QiAttribute METAL = of("meridian:metal");
        public static final QiAttribute EARTH = of("meridian:earth");

        public QiAttribute {
            // 注册与能力校验属于内容层，这里只保证属性有稳定身份。
            MeridianErrors.required(id, Data.QI_ATTRIBUTE_ID_REQUIRED);
        }

        public static QiAttribute of(String id) {
            // 复用内容 ID 的语法校验，避免内置与第三方属性走不同规则。
            return new QiAttribute(new DefinitionId(id));
        }
    }
}
