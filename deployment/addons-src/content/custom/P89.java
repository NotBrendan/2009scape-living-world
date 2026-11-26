package content.custom;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * P89: a NavWalker route kept across a teleport or climb (the design notes).
 * Runner.walk() ends a walk within 2 tiles of dest before NavWalker clears its State, so after an emergency
 * teleport the same goal reuses the stale last hop and the engine walk ends in the Falador wall nook (2937,3385).
 * Fix: plan again when the bot jumped more than JUMP tiles since the last step(), and forget the route on arrival.
 * Flag -Dnav.p89Fix=true, default off.
 */
final class P89 {
    private P89() { }
    static final boolean ON = Boolean.getBoolean("nav.p89Fix");
    static final AtomicInteger STALE = new AtomicInteger(), ARRIVE = new AtomicInteger();
    static final int JUMP = 25; // tiles between two step() calls that no walk covers

    static void count(String what, AtomicInteger c) { int n = c.incrementAndGet(); if (n <= 5 || n % 50 == 0) System.out.println("[P89] " + what + " " + n); }

    /** True when the bot is more than JUMP tiles (either axis) from where step() last saw it. */
    static boolean jumped(int lx, int ly, int hx, int hy) { return Math.abs(hx - lx) > JUMP || Math.abs(hy - ly) > JUMP; }
}
