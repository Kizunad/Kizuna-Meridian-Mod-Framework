package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.pb.BodyMessages;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FillModuleTags;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.ModuleTagAssignment;

import java.util.HashSet;
import java.util.List;

import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.INVALID_REQUEST;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.MODULE_NOT_FOUND;
import static dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode.REVISION_EXHAUSTED;

/** 可信构型维护只补空标签；命令执行与事务暂存复算共用同一规则。 */
public final class ModuleTagExecution {
    private static final int MAX_TAGS = 16;

    private ModuleTagExecution() {
        // 标签维护不持有模板目录，具体标签由经过授权的内容宿主提供。
    }

    public static void validate(FillModuleTags request) {
        // 协议先限制工作量和唯一目标，实际模块存在性在锁定身体后检查。
        if (request.getModulesCount() == 0 || request.getModulesCount() > MeridianProtocol.MAX_LIST_ENTRIES) {
            throw MeridianErrors.invalid(MeridianErrors.INVALID_MODULE_TAGS);
        }
        var targets = new HashSet<String>();
        for (ModuleTagAssignment assignment : request.getModulesList()) {
            String id = switch (assignment.getModuleCase()) {
                case TISSUE_ID -> new Anatomy.TissueId(assignment.getTissueId()).value();
                case ORGAN_ID -> new Anatomy.OrganId(assignment.getOrganId()).value();
                case MERIDIAN_ID -> new Anatomy.MeridianId(assignment.getMeridianId()).value();
                case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.INVALID_MODULE_TAGS);
            };
            List<String> tags = assignment.getTagsList();
            if (!targets.add(assignment.getModuleCase() + ":" + id) || tags.isEmpty()
                    || tags.size() > MAX_TAGS || new HashSet<>(tags).size() != tags.size()) {
                throw MeridianErrors.invalid(MeridianErrors.INVALID_MODULE_TAGS);
            }
            // 构造内容 ID 验证命名空间语法，失败时明确拒绝整笔请求。
            tags.forEach(DefinitionId::new);
        }
    }

    public static BodySnapshot apply(BodySnapshot body, FillModuleTags request) {
        // 保留实际构型及全部状态，只在权威候选上改指定模块标签与版本。
        validate(request);
        if (body.revision() == Long.MAX_VALUE) {
            throw MeridianErrors.rejectModuleTags(REVISION_EXHAUSTED);
        }
        var structure = BodyProtobuf.toStructure(body.structure()).toBuilder();
        for (ModuleTagAssignment assignment : request.getModulesList()) {
            switch (assignment.getModuleCase()) {
                case TISSUE_ID -> fillTissue(structure, assignment);
                case ORGAN_ID -> fillOrgan(structure, assignment);
                case MERIDIAN_ID -> fillMeridian(structure, assignment);
                case MODULE_NOT_SET -> throw MeridianErrors.invalid(MeridianErrors.INVALID_MODULE_TAGS);
            }
        }
        BodyMessages.BodySnapshot updated = BodyProtobuf.toMessage(body).toBuilder()
                .setStructure(structure.build())
                .setRevision(body.revision() + 1)
                .build();
        return BodyProtobuf.fromMessage(updated);
    }

    private static void fillTissue(BodyMessages.BodyStructure.Builder structure, ModuleTagAssignment assignment) {
        // 仅按组织实际 ID 定位，不能用类型或位置猜测替代模块。
        for (int index = 0; index < structure.getTissuesCount(); index++) {
            var tissue = structure.getTissues(index);
            if (tissue.getId().equals(assignment.getTissueId())) {
                requireCompatible(tissue.getTagsList(), assignment.getTagsList());
                if (tissue.getTagsCount() == 0) {
                    var updated = tissue.toBuilder().addAllTags(assignment.getTagsList()).build();
                    structure.setTissues(index, updated);
                }
                return;
            }
        }
        throw MeridianErrors.rejectModuleTags(MODULE_NOT_FOUND);
    }

    private static void fillOrgan(BodyMessages.BodyStructure.Builder structure, ModuleTagAssignment assignment) {
        // 器官与组织可以同名，目标种类必须保持隔离。
        for (int index = 0; index < structure.getOrgansCount(); index++) {
            var organ = structure.getOrgans(index);
            if (organ.getId().equals(assignment.getOrganId())) {
                requireCompatible(organ.getTagsList(), assignment.getTagsList());
                if (organ.getTagsCount() == 0) {
                    var updated = organ.toBuilder().addAllTags(assignment.getTagsList()).build();
                    structure.setOrgans(index, updated);
                }
                return;
            }
        }
        throw MeridianErrors.rejectModuleTags(MODULE_NOT_FOUND);
    }

    private static void fillMeridian(BodyMessages.BodyStructure.Builder structure, ModuleTagAssignment assignment) {
        // 开脉后体积等实际参数保留，只从请求填入标签集合。
        for (int index = 0; index < structure.getMeridiansCount(); index++) {
            var meridian = structure.getMeridians(index);
            if (meridian.getId().equals(assignment.getMeridianId())) {
                requireCompatible(meridian.getTagsList(), assignment.getTagsList());
                if (meridian.getTagsCount() == 0) {
                    var updated = meridian.toBuilder().addAllTags(assignment.getTagsList()).build();
                    structure.setMeridians(index, updated);
                }
                return;
            }
        }
        throw MeridianErrors.rejectModuleTags(MODULE_NOT_FOUND);
    }

    private static void requireCompatible(List<String> existing, List<String> expected) {
        // 标签是集合；非空集合只允许相同内容，保持原顺序而不覆盖。
        if (!existing.isEmpty() && !new HashSet<>(existing).equals(new HashSet<>(expected))) {
            throw MeridianErrors.rejectModuleTags(INVALID_REQUEST);
        }
    }
}
