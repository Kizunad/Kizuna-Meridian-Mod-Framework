package dev.kizuna.meridian.server;

import dev.kizuna.meridian.protocol.pb.BodyMessages.*;
import dev.kizuna.meridian.protocol.pb.EnvironmentMessages.EnvironmentQiSource;
import dev.kizuna.meridian.protocol.pb.GatheringMessages.*;
import dev.kizuna.meridian.error.MeridianErrors;
import dev.kizuna.meridian.protocol.MeridianProtocol;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.MeridianMessages.FailureCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 从实际构型与宿主允许的配方派生选项，不保存另一个身体或环境账户。 */
final class GatheringChoices {
    private GatheringChoices() {
        // 查询只使用本次快照，改造后的端口与容量会自然影响下一次选项。
    }

    static GatheringEnvironment environment(Optional<EnvironmentQiSource> source) {
        // 预览不发放供给额度；真正摄入仍在框架事务内读取最新区域及共享预算。
        var preview = GatheringEnvironment.newBuilder().setQi(QiInventory.getDefaultInstance());
        if (source.isPresent()) {
            EnvironmentQiSource actual = source.get();
            preview.setAvailable(true).setQi(actual.getInventory())
                    .setConcentrationPerBlock(total(actual.getInventory()) / actual.getSpace().getVolumeBlocks())
                    .setSupplyPerSecond(actual.getMaxAbsorptionPerSecond());
        }
        return preview.build();
    }

    static List<GatheringChoice> list(dev.kizuna.meridian.core.BodySnapshot actual,
                                      OrganRecipeCatalog catalog, double supplyPerSecond) {
        // 只呈现有摄入能力的器官和直接接入经脉的真实线路，不推断人体左右关系。
        BodySnapshot body = BodyProtobuf.toMessage(actual);
        var choices = new ArrayList<GatheringChoice>();
        List<OrganRecipe> recipes = catalog.definitions();
        for (OrganDefinition organ : body.getStructure().getOrgansList()) {
            if (!organ.hasIntake()) {
                continue;
            }
            List<String> outputPorts = organ.getPortsList().stream().map(Port::getId).toList();
            for (Connection connection : body.getStructure().getConnectionsList()) {
                if (!outputPorts.contains(connection.getFrom())) {
                    continue;
                }
                Optional<MeridianDefinition> target = body.getStructure().getMeridiansList().stream()
                        .filter(meridian -> meridian.getPortsList().stream()
                                .anyMatch(port -> port.getId().equals(connection.getTo())))
                        .findFirst();
                if (target.isEmpty()) {
                    continue;
                }
                MeridianDefinition meridian = target.get();
                MeridianState state = body.getMeridianStatesList().stream()
                        .filter(value -> value.getId().equals(meridian.getId())).findFirst().orElseThrow();
                // 开脉和普通摄入使用同一稳定空间，预览只扣实际体积和预约。
                double capacity = meridian.getLumenVolume();
                double freeCapacity = capacity - dev.kizuna.meridian.protocol.QiDensity.volume(state.getLumenQi())
                        - reserved(body, meridian.getPortsList());
                for (OrganRecipe recipe : recipes) {
                    if (recipe.getOrganKind().equals(organ.getKind())
                            && recipe.getMinimumRealmValue() <= body.getRealmValue() && total(recipe.getOutput()) > 0) {
                        if (choices.size() == MeridianProtocol.MAX_LIST_ENTRIES) {
                            // 配方与线路的组合也服从协议列表上限，宿主可缩小本次允许目录。
                            throw MeridianErrors.rejectGathering(FailureCode.NUMERIC_LIMIT);
                        }
                        choices.add(GatheringChoice.newBuilder().setOrganId(organ.getId()).setRecipe(recipe)
                                .setConnectionId(connection.getId()).setTargetMeridianId(meridian.getId())
                                .setTargetRemainingVolume(Math.max(0, freeCapacity))
                                .setIntakePerSecond(Math.min(supplyPerSecond, organ.getIntake().getMaxQiPerSecond()))
                                .build());
                    }
                }
            }
        }
        return List.copyOf(choices);
    }

    static double reserved(BodySnapshot body, List<Port> ports) {
        // 阻塞批次仍占目标预约容量，不能被新聚气抢占。
        List<String> portIds = ports.stream().map(Port::getId).toList();
        List<String> inbound = body.getStructure().getConnectionsList().stream()
                .filter(connection -> portIds.contains(connection.getTo())).map(Connection::getId).toList();
        return body.getTransitsList().stream().filter(transit -> inbound.contains(transit.getConnectionId()))
                .mapToDouble(transit -> dev.kizuna.meridian.protocol.QiDensity.volume(transit.getCargo())).sum();
    }

    static double total(QiInventory inventory) {
        // 库存类型保持不变，此处汇总仅用于容量和时长预估。
        return inventory.getEntriesList().stream().mapToDouble(Qi::getAmount).sum();
    }
}
