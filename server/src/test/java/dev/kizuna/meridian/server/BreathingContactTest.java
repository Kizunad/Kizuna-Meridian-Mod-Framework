package dev.kizuna.meridian.server;

import org.junit.Test;
import static org.junit.Assert.*;

/** 保护口鼻接触判定，避免低频运动采样漏掉穿过入口的粒子。 */
public class BreathingContactTest {
    @Test
    public void acceptsNearbyAndCrossingParticlesButRejectsDepthOverlap() {
        // 起点或终点在接收区内都算接触；两端在外但轨迹穿过也必须接收。
        assertTrue(BreathingDriver.touchesMouth(.55, .4, .5, .55, .4, .5));
        assertTrue(BreathingDriver.touchesMouth(.5, .4, .5, .8, .4, .5));
        assertTrue(BreathingDriver.touchesMouth(.2, .4, .5, .8, .4, .5));
        assertFalse(BreathingDriver.touchesMouth(.5, .4, .8, .5, .4, .8));
        assertFalse(BreathingDriver.touchesMouth(.2, .6, .5, .8, .6, .5));
    }
}
