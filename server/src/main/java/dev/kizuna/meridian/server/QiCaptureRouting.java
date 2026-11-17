package dev.kizuna.meridian.server;

import dev.kizuna.meridian.core.Anatomy;
import dev.kizuna.meridian.core.BodySnapshot;
import dev.kizuna.meridian.core.DefinitionId;
import dev.kizuna.meridian.core.MeridianOpening;
import dev.kizuna.meridian.core.MeridianState;
import dev.kizuna.meridian.core.Qi;
import dev.kizuna.meridian.core.QiInventory;
import dev.kizuna.meridian.protocol.pb.BodyMessages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 即时摄入产物的去处：配方给某种属性写了经脉标签，这一样就不进器官缓存，直接记进带这个标签、已经开拓的经脉内腔。
 * 按经脉 ID 先后装，一条装不下顺延下一条；没有这样的经脉、或者都装不下的部分，
 * 照同样的量和密度算成无属性留在器官缓存里。
 */
final class QiCaptureRouting {
    private QiCaptureRouting() {
        // 只有静态换算，不保留身体状态。
    }

    /** 留在器官缓存里的，和直接记进各条经脉内腔的。 */
    record Routing(QiInventory kept, Map<Anatomy.MeridianId, QiInventory> lumen) {
        QiInventory routed() {
            // 直接进了经脉的合计，给吐纳的入脉统计用。
            return QiInventory.combine(List.copyOf(lumen.values()));
        }
    }

    static Routing of(BodySnapshot body, BodyMessages.OrganRecipe recipe, QiInventory output) {
        // 没写去处的配方原样全留在器官里。
        if (recipe.getOutputRoutesCount() == 0) {
            return new Routing(output, Map.of());
        }
        Map<String, String> tags = new HashMap<>();
        recipe.getOutputRoutesList().forEach(route -> tags.put(route.getAttribute(), route.getMeridianTag()));
        List<QiInventory> kept = new ArrayList<>();
        Map<Anatomy.MeridianId, List<QiInventory>> lumen = new LinkedHashMap<>();
        Map<Anatomy.MeridianId, Double> taken = new HashMap<>();
        for (Qi qi : output.entries()) {
            String tag = tags.get(qi.attribute().id().value());
            double left = qi.amount();
            if (tag != null) {
                left = pour(body, qi, new DefinitionId(tag), lumen, taken);
            } else if (left > 0) {
                kept.add(new QiInventory(List.of(qi)));
                left = 0;
            }
            if (left > 0) {
                kept.add(new QiInventory(List.of(new Qi(Qi.QiAttribute.NONE, left, qi.density()))));
            }
        }
        Map<Anatomy.MeridianId, QiInventory> credits = new LinkedHashMap<>();
        lumen.forEach((id, parts) -> credits.put(id, QiInventory.combine(parts)));
        return new Routing(QiInventory.combine(kept), credits);
    }

    private static double pour(BodySnapshot body, Qi qi, DefinitionId tag,
                               Map<Anatomy.MeridianId, List<QiInventory>> lumen,
                               Map<Anatomy.MeridianId, Double> taken) {
        // 往带标签、开着的经脉里按 ID 先后装；返回没装下的量。
        double left = qi.amount();
        List<Anatomy.MeridianDefinition> targets = body.structure().meridians().stream()
                .filter(meridian -> meridian.tags().contains(tag))
                .sorted(Comparator.comparing(meridian -> meridian.id().value())).toList();
        for (Anatomy.MeridianDefinition meridian : targets) {
            if (left <= 0) {
                break;
            }
            double room = room(body, meridian) - taken.getOrDefault(meridian.id(), 0.0);
            double fits = Math.min(left, room * qi.density());
            if (fits <= 0) {
                continue;
            }
            lumen.computeIfAbsent(meridian.id(), id -> new ArrayList<>())
                    .add(new QiInventory(List.of(new Qi(qi.attribute(), fits, qi.density()))));
            taken.merge(meridian.id(), fits / qi.density(), Double::sum);
            left -= fits;
        }
        return Math.max(0, left);
    }

    private static double room(BodySnapshot body, Anatomy.MeridianDefinition meridian) {
        // 没开拓的经脉不收；开着的按内腔稳定体积减去已有的和在途预约的算。
        MeridianState state = body.meridianStates().stream()
                .filter(item -> item.id().equals(meridian.id())).findFirst().orElseThrow();
        if (state.opening().stage() != MeridianOpening.Stage.OPENED) {
            return 0;
        }
        List<Anatomy.PortId> ports = meridian.ports().stream().map(Anatomy.Port::id).toList();
        List<Anatomy.ConnectionId> inbound = body.structure().connections().stream()
                .filter(connection -> ports.contains(connection.to())).map(Anatomy.Connection::id).toList();
        double reserved = body.transits().stream().filter(transit -> inbound.contains(transit.connectionId()))
                .mapToDouble(transit -> transit.cargo().volume()).sum();
        return Math.max(0, meridian.lumenVolume() - state.lumenQi().volume() - reserved);
    }
}
