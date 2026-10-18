//! `PlayerSim` (vanilla player movement) over a [`Snap`], a copy of a box of cells. A line-for-line port of
//! `baritone.pathing.kinematic.PlayerSim`: same operations in the same order, so the same bits come out.

use crate::jmath::{cos, floor_i, jabs, jmax, jmin, sin, to_radians};
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};

pub const HALF_WIDTH: f64 = 0.3f32 as f64;
pub const HEIGHT: f64 = 1.8f32 as f64;
const STEP: f64 = 0.6;
const CLIMB_LIMIT: f64 = 0.15;
const CLIMB_SPEED: f64 = 0.2;
const HONEY_REACH: f64 = 0.4375 + HALF_WIDTH;
const HONEY_TOP: f64 = 0.9375;

pub const F_BOUNCY: u8 = 1;
pub const F_WATER: u8 = 2;
pub const F_CLIMBABLE: u8 = 4;
pub const F_STICKY: u8 = 8;

pub type Box6 = [f64; 6];

/// A dense box of cells: each cell's collision boxes (world coordinates, `box_start[i]..box_start[i + 1]` in `boxes`),
/// flags and friction/speed/jump factors. Reading outside it sets `oob` and reads air: the caller throws the run away.
pub struct Snap<'a> {
    pub x0: i32,
    pub y0: i32,
    pub z0: i32,
    pub nx: i32,
    pub ny: i32,
    pub nz: i32,
    pub box_start: &'a [i32],
    pub boxes: &'a [f64],
    pub flags: &'a [u8],
    pub factors: &'a [f32],
    pub oob: AtomicBool,
}

impl Snap<'_> {
    /// Whether anything read outside the snapshot so far.
    #[inline]
    pub fn missed(&self) -> bool {
        self.oob.load(Relaxed)
    }

    #[inline]
    fn idx(&self, x: i32, y: i32, z: i32) -> Option<usize> {
        let dx = x as i64 - self.x0 as i64;
        let dy = y as i64 - self.y0 as i64;
        let dz = z as i64 - self.z0 as i64;
        if dx < 0 || dy < 0 || dz < 0 || dx >= self.nx as i64 || dy >= self.ny as i64 || dz >= self.nz as i64 {
            self.oob.store(true, Relaxed);
            return None;
        }
        Some(((dx * self.ny as i64 + dy) * self.nz as i64 + dz) as usize)
    }

    /// `World.collect`: every box of the cells under the area, one layer lower too, x then y then z.
    pub fn collect(&self, min_x: f64, min_y: f64, min_z: f64, max_x: f64, max_y: f64, max_z: f64, out: &mut Vec<Box6>) {
        let (x1, y1, z1) = (floor_i(max_x), floor_i(max_y), floor_i(max_z));
        let (xa, ya, za) = (floor_i(min_x), floor_i(min_y) as i64 - 1, floor_i(min_z));
        if (xa as i64) < self.x0 as i64 || ya < self.y0 as i64 || (za as i64) < self.z0 as i64
            || x1 as i64 >= self.x0 as i64 + self.nx as i64 || y1 as i64 >= self.y0 as i64 + self.ny as i64
            || z1 as i64 >= self.z0 as i64 + self.nz as i64
        {
            // an empty range (min > max) reads nothing in Java either; anything else outside is a miss
            if xa <= x1 && ya <= y1 as i64 && za <= z1 {
                self.oob.store(true, Relaxed);
            }
            return;
        }
        let ya = ya as i32;
        for x in xa..=x1 {
            for y in ya..=y1 {
                let base = (((x - self.x0) as usize * self.ny as usize) + (y - self.y0) as usize) * self.nz as usize;
                for z in za..=z1 {
                    let i = base + (z - self.z0) as usize;
                    let (s, e) = (self.box_start[i] as usize, self.box_start[i + 1] as usize);
                    for b in s..e {
                        let o = b * 6;
                        out.push([self.boxes[o], self.boxes[o + 1], self.boxes[o + 2], self.boxes[o + 3], self.boxes[o + 4], self.boxes[o + 5]]);
                    }
                }
            }
        }
    }

    #[inline]
    fn flag(&self, x: i32, y: i32, z: i32, f: u8) -> bool {
        self.idx(x, y, z).is_some_and(|i| self.flags[i] & f != 0)
    }

    #[inline]
    fn factor(&self, x: i32, y: i32, z: i32, k: usize, air: f32) -> f32 {
        self.idx(x, y, z).map_or(air, |i| self.factors[i * 3 + k])
    }

    pub fn slipperiness(&self, x: i32, y: i32, z: i32) -> f32 {
        self.factor(x, y, z, 0, 0.6)
    }
    pub fn speed_factor(&self, x: i32, y: i32, z: i32) -> f32 {
        self.factor(x, y, z, 1, 1.0)
    }
    pub fn jump_factor(&self, x: i32, y: i32, z: i32) -> f32 {
        self.factor(x, y, z, 2, 1.0)
    }
    pub fn bouncy(&self, x: i32, y: i32, z: i32) -> bool {
        self.flag(x, y, z, F_BOUNCY)
    }
    pub fn water(&self, x: i32, y: i32, z: i32) -> bool {
        self.flag(x, y, z, F_WATER)
    }
    pub fn climbable(&self, x: i32, y: i32, z: i32) -> bool {
        self.flag(x, y, z, F_CLIMBABLE)
    }
    pub fn sticky(&self, x: i32, y: i32, z: i32) -> bool {
        self.flag(x, y, z, F_STICKY)
    }
}

/// The player's state; the world and the box buffer are passed in, so a state is a plain copyable value.
#[derive(Clone, Copy, Debug, Default)]
pub struct Sim {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub vx: f64,
    pub vy: f64,
    pub vz: f64,
    pub on_ground: bool,
    pub sprinting: bool,
    pub collided_h: bool,
    pub swimming: bool,
    pub jump_ticks: i32,
    pub speed_scale: f64,
    pub jump_strength: f64,
    pub jump_boost: f64,
    pub movement_efficiency: f64,
}

pub const STATE_LEN: usize = 15;

impl Sim {
    pub fn read(s: &[f64]) -> Sim {
        Sim {
            x: s[0],
            y: s[1],
            z: s[2],
            vx: s[3],
            vy: s[4],
            vz: s[5],
            on_ground: s[6] != 0.0,
            sprinting: s[7] != 0.0,
            collided_h: s[8] != 0.0,
            swimming: s[9] != 0.0,
            jump_ticks: s[10] as i32,
            speed_scale: s[11],
            jump_strength: s[12],
            jump_boost: s[13],
            movement_efficiency: s[14],
        }
    }

    pub fn write(&self, s: &mut [f64]) {
        let b = |v: bool| if v { 1.0 } else { 0.0 };
        s[..STATE_LEN].copy_from_slice(&[
            self.x, self.y, self.z, self.vx, self.vy, self.vz, b(self.on_ground), b(self.sprinting), b(self.collided_h),
            b(self.swimming), self.jump_ticks as f64, self.speed_scale, self.jump_strength, self.jump_boost,
            self.movement_efficiency,
        ]);
    }

    /// `PlayerSim.tick(yaw, input, strafe, sprint, jump)`.
    pub fn tick(&mut self, w: &Snap, buf: &mut Vec<Box6>, yaw_deg: f32, input: i32, strafe: i32, sprint: bool, jump: bool) {
        let forward = input > 0;
        if jabs(self.vx) < 0.003 {
            self.vx = 0.0;
        }
        if jabs(self.vy) < 0.003 {
            self.vy = 0.0;
        }
        if jabs(self.vz) < 0.003 {
            self.vz = 0.0;
        }
        self.sprinting = forward && !self.collided_h && (sprint || self.sprinting);
        let yaw = to_radians(yaw_deg as f64);
        let (sn, cs) = (sin(yaw), cos(yaw));
        if self.jump_ticks > 0 {
            self.jump_ticks -= 1;
        }
        if !jump {
            self.jump_ticks = 0;
        }
        if jump && self.on_ground && self.jump_ticks == 0 {
            self.jump_ticks = 10;
            self.vy = self.jump_strength * self.jump_factor(w) as f64 + self.jump_boost;
            if self.sprinting {
                self.vx -= sn * 0.2;
                self.vz += cs * 0.2;
            }
        }
        let block_slip: f32 = if self.on_ground { w.slipperiness(floor_i(self.x), floor_i(self.y - 0.5000001), floor_i(self.z)) } else { 1.0 };
        let slip = if self.on_ground { block_slip as f64 * 0.91 } else { 0.91 };
        let speed = if self.on_ground {
            let mv = (if self.sprinting { 0.13 } else { 0.1 }) * self.speed_scale;
            mv * (0.21600002 / ((block_slip * block_slip * block_slip) as f64))
        } else if self.sprinting {
            0.026
        } else {
            0.02
        };
        if input != 0 || strafe != 0 {
            let mut fz = 0.98 * input as f64;
            let mut fx = 0.98 * strafe as f64;
            let len = (fx * fx + fz * fz).sqrt();
            if len > 1.0 {
                fx /= len;
                fz /= len;
            }
            fx *= speed;
            fz *= speed;
            self.vx += fx * cs - fz * sn;
            self.vz += fz * cs + fx * sn;
        }
        if self.climbable(w) {
            self.vx = jmax(-CLIMB_LIMIT, jmin(CLIMB_LIMIT, self.vx));
            self.vz = jmax(-CLIMB_LIMIT, jmin(CLIMB_LIMIT, self.vz));
            self.vy = jmax(self.vy, -CLIMB_LIMIT);
        }
        self.mv(w, buf, self.vx, self.vy, self.vz);
        if (self.collided_h || jump) && self.climbable(w) {
            self.vy = CLIMB_SPEED;
        }
        self.vy = (self.vy - 0.08) * 0.98;
        self.vx *= slip;
        self.vz *= slip;
    }

    pub fn in_water(&self, w: &Snap) -> bool {
        w.water(floor_i(self.x), floor_i(self.y + 0.1), floor_i(self.z))
    }

    pub fn eyes_in_water(&self, w: &Snap) -> bool {
        w.water(floor_i(self.x), floor_i(self.y + 1.62 - 0.11), floor_i(self.z))
    }

    /// `PlayerSim.tickWater(yaw, pitch, forward, sprint, jump, sneak)`.
    #[allow(clippy::too_many_arguments)]
    pub fn tick_water(&mut self, w: &Snap, buf: &mut Vec<Box6>, yaw_deg: f32, pitch_deg: f32, forward: bool, sprint: bool, jump: bool, sneak: bool) {
        if jabs(self.vx) < 0.003 {
            self.vx = 0.0;
        }
        if jabs(self.vy) < 0.003 {
            self.vy = 0.0;
        }
        if jabs(self.vz) < 0.003 {
            self.vz = 0.0;
        }
        self.sprinting = forward && !self.collided_h && (sprint || self.sprinting);
        self.swimming = self.sprinting && if self.swimming { self.in_water(w) } else { self.eyes_in_water(w) };
        let yaw = to_radians(yaw_deg as f64);
        let (sn, cs) = (sin(yaw), cos(yaw));
        if self.swimming {
            let look = -sin(to_radians(pitch_deg as f64));
            let k = if look < -0.2 { 0.085 } else { 0.06 };
            if look <= 0.0 || jump || w.water(floor_i(self.x), floor_i(self.y + 1.0 - 0.1), floor_i(self.z)) {
                self.vy += (look - self.vy) * k;
            }
        }
        if forward {
            let f = 0.98 * 0.02;
            self.vx += -sn * f;
            self.vz += cs * f;
        }
        if jump {
            self.vy += 0.04;
        }
        if sneak {
            self.vy -= 0.04;
        }
        let start_y = self.y;
        self.mv(w, buf, self.vx, self.vy, self.vz);
        let drag = if self.sprinting { 0.9 } else { 0.8 };
        self.vx *= drag;
        self.vz *= drag;
        self.vy *= 0.8;
        if !self.sprinting {
            self.vy -= 0.08 / 16.0;
        }
        if self.collided_h {
            let dy = self.vy + 0.6 - self.y + start_y;
            let r = collide(w, buf, self.x, self.y, self.z, self.vx, dy, self.vz);
            if r[0] == self.vx && r[1] == dy && r[2] == self.vz && !w.water(floor_i(self.x + self.vx), floor_i(self.y + dy), floor_i(self.z + self.vz)) {
                self.vy = 0.3;
            }
        }
    }

    pub fn climbable(&self, w: &Snap) -> bool {
        w.climbable(floor_i(self.x), floor_i(self.y), floor_i(self.z))
    }

    fn mv(&mut self, w: &Snap, buf: &mut Vec<Box6>, dx: f64, dy: f64, dz: f64) {
        let (ox, oy, oz) = (dx, dy, dz);
        let mut r = collide(w, buf, self.x, self.y, self.z, dx, dy, dz);
        let ground_after = oy != r[1] && oy < 0.0;
        if (self.on_ground || ground_after) && (ox != r[0] || oz != r[2]) {
            let mut up = collide(w, buf, self.x, self.y, self.z, dx, STEP, dz);
            let up_only = collide(w, buf, self.x, self.y, self.z, 0.0, STEP, 0.0);
            if up_only[1] < STEP {
                let alt = collide(w, buf, self.x, self.y, self.z, dx, up_only[1], dz);
                if alt[0] * alt[0] + alt[2] * alt[2] > up[0] * up[0] + up[2] * up[2] {
                    up = alt;
                }
            }
            let down = collide(w, buf, self.x + up[0], self.y + up[1], self.z + up[2], 0.0, -up[1] + oy, 0.0);
            up[1] += down[1];
            if up[0] * up[0] + up[2] * up[2] > r[0] * r[0] + r[2] * r[2] {
                r = up;
            }
        }
        self.x += r[0];
        self.y += r[1];
        self.z += r[2];
        self.collided_h = jabs(ox - r[0]) >= 1e-5 || jabs(oz - r[2]) >= 1e-5;
        self.on_ground = oy != r[1] && oy < 0.0;
        if ox != r[0] {
            self.vx = 0.0;
        }
        if oz != r[2] {
            self.vz = 0.0;
        }
        if oy != r[1] {
            self.vy = 0.0;
        }
        let slime = w.bouncy(floor_i(self.x), floor_i(self.y - 0.2), floor_i(self.z));
        if slime && self.on_ground && oy < 0.0 {
            self.vy = -oy;
        }
        if slime && self.on_ground && jabs(self.vy) < 0.1 {
            let k = 0.4 + jabs(self.vy) * 0.2;
            self.vx *= k;
            self.vz *= k;
        }
        self.honey_slide(w);
        let mut drag = self.speed_factor(w);
        if drag != 1.0 {
            drag += self.movement_efficiency as f32 * (1.0 - drag);
            self.vx *= drag as f64;
            self.vz *= drag as f64;
        }
    }

    fn speed_factor(&self, w: &Snap) -> f32 {
        let (cx, cy, cz) = (floor_i(self.x), floor_i(self.y), floor_i(self.z));
        let f = w.speed_factor(cx, cy, cz);
        let below = floor_i(self.y - 0.500001);
        if f != 1.0 || below == cy {
            return f;
        }
        let f = w.speed_factor(cx, below, cz);
        if f != 1.0 && w.water(cx, cy, cz) { 1.0 } else { f }
    }

    fn jump_factor(&self, w: &Snap) -> f32 {
        let (cx, cy, cz) = (floor_i(self.x), floor_i(self.y), floor_i(self.z));
        let f = w.jump_factor(cx, cy, cz);
        let below = floor_i(self.y - 0.500001);
        if f != 1.0 || below == cy { f } else { w.jump_factor(cx, below, cz) }
    }

    fn honey_slide(&mut self, w: &Snap) {
        if self.on_ground || self.vy >= -0.08 {
            return;
        }
        let mut cx = floor_i(self.x - HALF_WIDTH + 1e-5);
        while cx <= floor_i(self.x + HALF_WIDTH - 1e-5) {
            let mut cz = floor_i(self.z - HALF_WIDTH + 1e-5);
            while cz <= floor_i(self.z + HALF_WIDTH - 1e-5) {
                if !(jabs(cx as f64 + 0.5 - self.x) + 1e-7 <= HONEY_REACH && jabs(cz as f64 + 0.5 - self.z) + 1e-7 <= HONEY_REACH) {
                    let mut cy = floor_i(self.y + 1e-5);
                    while cy <= floor_i(self.y + HEIGHT - 1e-5) {
                        if !(self.y > cy as f64 + HONEY_TOP - 1e-7 || !w.sticky(cx, cy, cz)) {
                            if self.vy < -0.13 {
                                let k = -0.05 / self.vy;
                                self.vx *= k;
                                self.vz *= k;
                            }
                            self.vy = -0.05;
                            return;
                        }
                        cy += 1;
                    }
                }
                cz += 1;
            }
            cx += 1;
        }
    }
}

/// `PlayerSim.collide`: Y, then the larger horizontal axis first.
pub fn collide(w: &Snap, buf: &mut Vec<Box6>, px: f64, py: f64, pz: f64, mut dx: f64, mut dy: f64, mut dz: f64) -> [f64; 3] {
    let (mut min_x, mut max_x) = (px - HALF_WIDTH, px + HALF_WIDTH);
    let (mut min_y, mut max_y) = (py, py + HEIGHT);
    let (mut min_z, mut max_z) = (pz - HALF_WIDTH, pz + HALF_WIDTH);
    buf.clear();
    w.collect(
        jmin(min_x, min_x + dx) - 1e-7,
        jmin(min_y, min_y + dy) - 1e-7,
        jmin(min_z, min_z + dz) - 1e-7,
        jmax(max_x, max_x + dx) + 1e-7,
        jmax(max_y, max_y + dy) + 1e-7,
        jmax(max_z, max_z + dz) + 1e-7,
        buf,
    );
    dy = clip(buf, 1, dy, [min_x, min_y, min_z, max_x, max_y, max_z]);
    min_y += dy;
    max_y += dy;
    if jabs(dx) < jabs(dz) {
        dz = clip(buf, 2, dz, [min_x, min_y, min_z, max_x, max_y, max_z]);
        min_z += dz;
        max_z += dz;
        dx = clip(buf, 0, dx, [min_x, min_y, min_z, max_x, max_y, max_z]);
    } else {
        dx = clip(buf, 0, dx, [min_x, min_y, min_z, max_x, max_y, max_z]);
        min_x += dx;
        max_x += dx;
        dz = clip(buf, 2, dz, [min_x, min_y, min_z, max_x, max_y, max_z]);
    }
    let _ = (max_x, max_z, min_z);
    [dx, dy, dz]
}

#[inline]
fn clip(boxes: &[Box6], a: usize, mut d: f64, me: [f64; 6]) -> f64 {
    if d == 0.0 {
        return 0.0;
    }
    let b = (a + 1) % 3;
    let c = (a + 2) % 3;
    for bx in boxes {
        if bx[3 + b] <= me[b] + 1e-7 || bx[b] >= me[3 + b] - 1e-7 {
            continue;
        }
        if bx[3 + c] <= me[c] + 1e-7 || bx[c] >= me[3 + c] - 1e-7 {
            continue;
        }
        if d > 0.0 && bx[a] >= me[3 + a] - 1e-7 {
            d = jmin(d, bx[a] - me[3 + a]);
        } else if d < 0.0 && bx[3 + a] <= me[a] + 1e-7 {
            d = jmax(d, bx[3 + a] - me[a]);
        }
    }
    d
}

/// `PlayerSim.feetCell`.
pub fn feet_cell(y: f64) -> i32 {
    floor_i(y + 0.1251)
}

/// `PlayerSim.nodeHeight`.
pub fn node_height(y: f64, node_y: i32) -> bool {
    feet_cell(y) == node_y || y < node_y as f64 && y >= node_y as f64 - 0.75 - 1e-9
}
