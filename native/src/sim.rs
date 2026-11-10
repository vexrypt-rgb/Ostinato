//! `PlayerSim` (vanilla 26.3 player movement) over a [`Snap`], a copy of a box of cells. A line-for-line port of
//! `baritone.pathing.kinematic.PlayerSim`: same operations in the same order, so the same bits come out.

use crate::jmath::{cos, floor_i, jabs, jmax, jmin, sin};
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};

pub const HALF_WIDTH: f64 = 0.3f32 as f64;
pub const HEIGHT: f64 = 1.8f32 as f64;
const STEP: f32 = 0.6;
const CLIMB_LIMIT: f64 = 0.15f32 as f64;
const CLIMB_SPEED: f64 = 0.2;
const GRAVITY: f64 = 0.08;
const WALK_SPEED: f64 = 0.1f32 as f64;
const SPRINT_BONUS: f64 = 0.3f32 as f64;
const HONEY_REACH: f64 = 0.4375 + HALF_WIDTH;
const HONEY_TOP: f64 = 0.9375;
/// `PlayerSim.AIR_DRAG` and `VERTICAL_DRAG`: `modifiedFriction` leaves a friction of 0.5 or more as it is.
const AIR_DRAG: f32 = 0.91;
const VERTICAL_DRAG: f32 = 0.98;
const BELOW_AFFECTING: f64 = 0.500001f32 as f64;
const ON_POS_LEGACY: f64 = 0.2f32 as f64;
const PI_F: f32 = std::f64::consts::PI as f32;

pub const F_BOUNCY: u8 = 1;
pub const F_WATER: u8 = 2;
pub const F_CLIMBABLE: u8 = 4;
pub const F_STICKY: u8 = 8;
/// Factors per cell: slipperiness, speed, jump, bounce.
pub const FACTORS: usize = 4;

pub type Box6 = [f64; 6];

struct Tables {
    sin: Vec<f32>,
    minor_cos: f64,
}

/// `PlayerSim.SIN` (Mth's table, from `StrictMath.sin`) and `MINOR_COS`.
fn tables() -> &'static Tables {
    static T: OnceLock<Tables> = OnceLock::new();
    T.get_or_init(|| Tables {
        sin: (0..65536).map(|i| sin(i as f64 / 10430.378350470453) as f32).collect(),
        minor_cos: cos(0.13962633907794952),
    })
}

/// `PlayerSim.msin` (Mth.sin).
#[inline]
pub fn msin(r: f64) -> f32 {
    tables().sin[((r * 10430.378350470453) as i64 & 0xFFFF) as usize]
}

/// `PlayerSim.mcos` (Mth.cos).
#[inline]
pub fn mcos(r: f64) -> f32 {
    tables().sin[((r * 10430.378350470453 + 16384.0) as i64 & 0xFFFF) as usize]
}

/// `PlayerSim.modifiedFriction`.
#[inline]
fn modified_friction(f: f32) -> f32 {
    let v = 1.0f32 - (1.0f32 - f) * 1.0f32;
    let m = if 1.0f32 <= v { 1.0f32 } else { v };
    if 0.0f32 >= m { 0.0 } else { m }
}

/// `Math.min(float, float)` for the values here (no NaN, no signed zero).
#[inline]
fn fmin(a: f32, b: f32) -> f32 {
    if a <= b { a } else { b }
}

/// `PlayerSim.keyInput`: the keys as xxa (+left) and zza (+forward), in floats.
pub fn key_input(input: i32, strafe: i32) -> (f32, f32) {
    let (mut mx, mut mz) = (strafe as f32, input as f32);
    let dist = ((mx * mx + mz * mz) as f64).sqrt() as f32;
    if dist < 1.0e-4f32 {
        return (0.0, 0.0);
    }
    mx /= dist;
    mz /= dist;
    mx *= 0.98f32;
    mz *= 0.98f32;
    let len = ((mx * mx + mz * mz) as f64).sqrt() as f32;
    if len <= 0.0 {
        return (mx, mz);
    }
    let inv = 1.0f32 / len;
    let (dx, dz) = (mx * inv, mz * inv);
    let (ax, az) = (dx.abs(), dz.abs());
    let tan = if az > ax { ax / az } else { az / ax };
    let to_square = ((1.0f32 + tan * tan) as f64).sqrt() as f32;
    let m = fmin(len * to_square, 1.0);
    (dx * m, dz * m)
}

/// `PlayerSim.ceil`.
#[inline]
fn ceil_i(v: f64) -> i32 {
    let i = v as i32;
    if v > i as f64 { i + 1 } else { i }
}

/// `Mth.equal`.
#[inline]
fn equal(a: f64, b: f64) -> bool {
    jabs(b - a) < 1.0e-5f32 as f64
}

/// A dense box of cells: each cell's collision boxes (world coordinates, `box_start[i]..box_start[i + 1]` in `boxes`),
/// flags and friction/speed/jump/bounce factors. Reading outside it sets `oob` and reads air: the caller throws the
/// run away.
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

    /// `World.cellBoxes` as a test: whether any box of the cell strictly overlaps the area; None outside the snapshot.
    fn cell_overlaps(&self, x: i32, y: i32, z: i32, a: &Box6) -> Option<bool> {
        let i = self.idx(x, y, z)?;
        let (s, e) = (self.box_start[i] as usize, self.box_start[i + 1] as usize);
        for bi in s..e {
            let b = &self.boxes[bi * 6..bi * 6 + 6];
            if b[0] < a[3] && b[3] > a[0] && b[1] < a[4] && b[4] > a[1] && b[2] < a[5] && b[5] > a[2] {
                return Some(true);
            }
        }
        Some(false)
    }

    #[inline]
    fn flag(&self, x: i32, y: i32, z: i32, f: u8) -> bool {
        self.idx(x, y, z).is_some_and(|i| self.flags[i] & f != 0)
    }

    #[inline]
    fn factor(&self, x: i32, y: i32, z: i32, k: usize, air: f32) -> f32 {
        self.idx(x, y, z).map_or(air, |i| self.factors[i * FACTORS + k])
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
    pub fn bounce(&self, x: i32, y: i32, z: i32) -> f32 {
        self.factor(x, y, z, 3, 0.0)
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

/// The player's state; the world and the box buffer are passed in, so a state is a plain copyable value. The fields
/// after `sup_z` live for one tick only and are not part of the marshalled state.
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
    pub minor_collision: bool,
    pub supported: bool,
    pub on_ground_no_blocks: bool,
    pub sup_x: i32,
    pub sup_y: i32,
    pub sup_z: i32,
    in_x: f64,
    in_z: f64,
    yaw_rad: f32,
    from_x: f64,
    from_y: f64,
    from_z: f64,
    z_first: bool,
}

pub const STATE_LEN: usize = 21;

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
            minor_collision: s[15] != 0.0,
            supported: s[16] != 0.0,
            on_ground_no_blocks: s[17] != 0.0,
            sup_x: s[18] as i32,
            sup_y: s[19] as i32,
            sup_z: s[20] as i32,
            ..Sim::default()
        }
    }

    pub fn write(&self, s: &mut [f64]) {
        let b = |v: bool| if v { 1.0 } else { 0.0 };
        s[..STATE_LEN].copy_from_slice(&[
            self.x, self.y, self.z, self.vx, self.vy, self.vz, b(self.on_ground), b(self.sprinting), b(self.collided_h),
            b(self.swimming), self.jump_ticks as f64, self.speed_scale, self.jump_strength, self.jump_boost,
            self.movement_efficiency, b(self.minor_collision), b(self.supported), b(self.on_ground_no_blocks),
            self.sup_x as f64, self.sup_y as f64, self.sup_z as f64,
        ]);
    }

    /// `PlayerSim.tick(yaw, input, strafe, sprint, jump)`.
    pub fn tick(&mut self, w: &Snap, buf: &mut Vec<Box6>, yaw_deg: f32, input: i32, strafe: i32, sprint: bool, jump: bool) {
        let (ix, iz) = key_input(input, strafe);
        self.tick_impulse(w, buf, yaw_deg, ix as f64, iz as f64, input > 0, sprint, jump);
    }

    /// `PlayerSim.tickImpulse(yaw, strafeImpulse, forwardImpulse, forward, sprint, jump)`.
    #[allow(clippy::too_many_arguments)]
    pub fn tick_impulse(&mut self, w: &Snap, buf: &mut Vec<Box6>, yaw_deg: f32, strafe_imp: f64, fwd_imp: f64, forward: bool, sprint: bool, jump: bool) {
        if self.vx * self.vx + self.vz * self.vz < 9.0e-6 {
            self.vx = 0.0;
            self.vz = 0.0;
        }
        if jabs(self.vy) < 0.003 {
            self.vy = 0.0;
        }
        self.sprinting = forward && !(self.collided_h && !self.minor_collision) && (sprint || self.sprinting);
        self.in_x = strafe_imp;
        self.in_z = fwd_imp;
        self.yaw_rad = yaw_deg * (PI_F / 180.0f32);
        if self.jump_ticks > 0 {
            self.jump_ticks -= 1;
        }
        if !jump {
            self.jump_ticks = 0;
        }
        if jump && self.on_ground && self.jump_ticks == 0 {
            self.jump_ticks = 10;
            self.jump_from_ground(w);
        }
        let block_friction: f32 = if self.on_ground {
            modified_friction(w.slipperiness(self.supported_x(), floor_i(self.y - BELOW_AFFECTING), self.supported_z()))
        } else {
            1.0
        };
        let speed: f32 = if self.on_ground {
            let mv = (WALK_SPEED * self.speed_scale * (if self.sprinting { 1.0 + SPRINT_BONUS } else { 1.0 })) as f32;
            if block_friction as f64 > 0.6 { mv * (0.21600002f32 / (block_friction * block_friction * block_friction)) } else { mv }
        } else if self.sprinting {
            0.025999999f32
        } else {
            0.02f32
        };
        self.move_relative(speed, strafe_imp, fwd_imp);
        if self.climbable(w) {
            self.vx = jmax(-CLIMB_LIMIT, jmin(CLIMB_LIMIT, self.vx));
            self.vz = jmax(-CLIMB_LIMIT, jmin(CLIMB_LIMIT, self.vz));
            self.vy = jmax(self.vy, -CLIMB_LIMIT);
        }
        self.mv(w, buf, self.vx, self.vy, self.vz);
        if (self.collided_h || jump) && self.climbable(w) {
            self.vy = CLIMB_SPEED;
        }
        self.vy = (self.vy - GRAVITY) * VERTICAL_DRAG as f64;
        let friction = block_friction * AIR_DRAG;
        self.vx *= friction as f64;
        self.vz *= friction as f64;
        self.after_travel(w);
    }

    /// `PlayerSim.jumpFromGround`.
    fn jump_from_ground(&mut self, w: &Snap) {
        let power = self.jump_strength as f32 * self.jump_factor(w) + self.jump_boost as f32;
        if power <= 1.0e-5f32 {
            return;
        }
        self.vy = jmax(power as f64, self.vy);
        if self.sprinting {
            self.vx += (-msin(self.yaw_rad as f64)) as f64 * 0.2;
            self.vz += mcos(self.yaw_rad as f64) as f64 * 0.2;
        }
    }

    /// `PlayerSim.moveRelative`.
    fn move_relative(&mut self, speed: f32, mut ix: f64, mut iz: f64) {
        let len_sq = ix * ix + iz * iz;
        let (mut mx, mut mz) = (0.0, 0.0);
        if len_sq >= 1.0e-7 {
            let mut zero = false;
            if len_sq > 1.0 {
                let dist = len_sq.sqrt();
                if dist < 1.0e-5f32 as f64 {
                    zero = true;
                } else {
                    ix /= dist;
                    iz /= dist;
                }
            }
            if !zero {
                ix *= speed as f64;
                iz *= speed as f64;
                let sn = msin(self.yaw_rad as f64) as f64;
                let cs = mcos(self.yaw_rad as f64) as f64;
                mx = ix * cs - iz * sn;
                mz = iz * cs + ix * sn;
            }
        }
        self.vx += mx;
        self.vz += mz;
    }

    /// `PlayerSim.afterTravel`.
    fn after_travel(&mut self, w: &Snap) {
        if self.on_ground && w.bouncy(self.supported_x(), self.legacy_y(), self.supported_z()) {
            let ay = jabs(self.vy);
            if ay < 0.1 {
                let k = 0.4 + ay * 0.2;
                self.vx *= k;
                self.vz *= k;
            }
        }
        self.honey_slide(w);
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
        let wet = self.in_water(w);
        let under = self.eyes_in_water(w);
        self.swimming = if self.swimming {
            self.sprinting && wet
        } else {
            self.sprinting && under && w.water(floor_i(self.x), floor_i(self.y), floor_i(self.z))
        };
        let shallow = wet && !under;
        if !self.sprinting && forward && !shallow && !(sneak && !under) && sprint {
            self.sprinting = true;
        }
        if self.sprinting {
            if self.swimming {
                if !wet || !forward && !self.on_ground && !sneak {
                    self.sprinting = false;
                }
            } else if shallow || !forward || self.collided_h && !self.minor_collision {
                self.sprinting = false;
            }
        }
        if wet && sneak {
            self.vy += (-0.04f32) as f64;
        }
        if self.vx * self.vx + self.vz * self.vz < 9.0e-6 {
            self.vx = 0.0;
            self.vz = 0.0;
        }
        if jabs(self.vy) < 0.003 {
            self.vy = 0.0;
        }
        let (ix, iz) = key_input(if forward { 1 } else { 0 }, 0);
        self.in_x = ix as f64;
        self.in_z = iz as f64;
        self.yaw_rad = yaw_deg * (PI_F / 180.0f32);
        if jump {
            self.vy += 0.04f32 as f64;
        }
        if self.swimming {
            let look = (-msin((pitch_deg * (PI_F / 180.0f32)) as f64)) as f64;
            let k = if look < -0.2 { 0.085 } else { 0.06 };
            if look <= 0.0 || jump || w.water(floor_i(self.x), floor_i(self.y + 1.0 - 0.1), floor_i(self.z)) {
                self.vy += (look - self.vy) * k;
            }
        }
        let falling = self.vy <= 0.0;
        let start_y = self.y;
        let slow_down: f32 = if self.sprinting { 0.9 } else { 0.8 };
        self.move_relative(0.02f32, self.in_x, self.in_z);
        self.mv(w, buf, self.vx, self.vy, self.vz);
        if self.collided_h && self.climbable(w) {
            self.vy = CLIMB_SPEED;
        }
        self.vx *= slow_down as f64;
        self.vy *= 0.8f32 as f64;
        self.vz *= slow_down as f64;
        if !self.sprinting {
            self.vy = if falling && jabs(self.vy - 0.005) >= 0.003 && jabs(self.vy - GRAVITY / 16.0) < 0.003 {
                -0.003
            } else {
                self.vy - GRAVITY / 16.0
            };
        }
        if self.collided_h {
            let dy = self.vy + 0.6f32 as f64 - self.y + start_y;
            if free(w, buf, self.x + self.vx, self.y + dy, self.z + self.vz) && !liquid_in(w, self.x + self.vx, self.y + dy, self.z + self.vz) {
                self.vy = 0.3f32 as f64;
            }
        }
        self.after_travel(w);
    }

    pub fn climbable(&self, w: &Snap) -> bool {
        w.climbable(floor_i(self.x), floor_i(self.y), floor_i(self.z))
    }

    /// `PlayerSim.move` (Entity.move).
    fn mv(&mut self, w: &Snap, buf: &mut Vec<Box6>, dx: f64, dy: f64, dz: f64) {
        self.from_x = self.x;
        self.from_y = self.y;
        self.from_z = self.z;
        self.z_first = jabs(dx) < jabs(dz);
        let r = self.collide_step(w, buf, dx, dy, dz);
        let m_len = r[0] * r[0] + r[1] * r[1] + r[2] * r[2];
        if m_len > 1.0e-7 || dx * dx + dy * dy + dz * dz - m_len < 1.0e-7 {
            self.x += r[0];
            self.y += r[1];
            self.z += r[2];
        }
        let x_coll = !equal(dx, r[0]);
        let z_coll = !equal(dz, r[2]);
        self.collided_h = x_coll || z_coll;
        let v_coll = dy != r[1];
        self.on_ground = v_coll && dy < 0.0;
        self.check_supporting_block(w, r[0], r[2]);
        self.minor_collision = self.collided_h && self.minor(r[0], r[2]);
        if jabs(dy) > 0.0 && v_coll || self.collided_h {
            let mut rest = 0.0f64;
            if x_coll {
                self.vx = -self.vx * rest;
            }
            if z_coll {
                self.vz = -self.vz * rest;
            }
            if v_coll {
                let cvy = self.vy;
                if self.on_ground {
                    rest = if -cvy <= GRAVITY {
                        0.0
                    } else {
                        jmax(rest, w.bounce(self.supported_x(), self.legacy_y(), self.supported_z()) as f64)
                    };
                }
                let (mut comp, mut drag) = (0.0f64, 1.0f64);
                if rest > 0.0 {
                    let portion = r[1] / cvy;
                    comp = portion * GRAVITY;
                    drag = 1.0 + portion * (VERTICAL_DRAG as f64 - 1.0);
                }
                self.vy = (comp - cvy) * drag * rest;
            }
        }
        let drag = self.speed_factor(w);
        if drag != 1.0 {
            self.vx *= drag as f64;
            self.vz *= drag as f64;
        }
    }

    /// `PlayerSim.collideStep` (Entity.collide).
    fn collide_step(&self, w: &Snap, buf: &mut Vec<Box6>, dx: f64, dy: f64, dz: f64) -> [f64; 3] {
        let r = if dx * dx + dy * dy + dz * dz == 0.0 { [dx, dy, dz] } else { collide(w, buf, self.x, self.y, self.z, dx, dy, dz) };
        let ground_after = dy != r[1] && dy < 0.0;
        if (ground_after || self.on_ground) && (dx != r[0] || dz != r[2]) {
            let gy = if ground_after { self.y + r[1] } else { self.y };
            let cands = self.step_candidates(w, buf, gy, dx, dz, ground_after, r[1] as f32);
            let before = r[0] * r[0] + r[2] * r[2];
            for h in cands {
                let mut s = collide(w, buf, self.x, gy, self.z, dx, h as f64, dz);
                if s[0] * s[0] + s[2] * s[2] > before {
                    s[1] -= self.y - gy;
                    return s;
                }
            }
        }
        r
    }

    /// `PlayerSim.stepCandidates`.
    #[allow(clippy::too_many_arguments)]
    fn step_candidates(&self, w: &Snap, buf: &mut Vec<Box6>, gy: f64, dx: f64, dz: f64, ground_after: bool, skip: f32) -> Vec<f32> {
        let min_x = self.x - HALF_WIDTH + jmin(dx, 0.0);
        let max_x = self.x + HALF_WIDTH + jmax(dx, 0.0);
        let min_z = self.z - HALF_WIDTH + jmin(dz, 0.0);
        let max_z = self.z + HALF_WIDTH + jmax(dz, 0.0);
        let min_y = if ground_after { gy } else { gy + (-1.0e-5f32) as f64 };
        let max_y = gy + HEIGHT + STEP as f64;
        buf.clear();
        w.collect(min_x, min_y, min_z, max_x, max_y, max_z, buf);
        let mut out: Vec<f32> = Vec::with_capacity(8);
        for b in buf.iter() {
            if !(b[0] < max_x && b[3] > min_x && b[1] < max_y && b[4] > min_y && b[2] < max_z && b[5] > min_z) {
                continue;
            }
            for k in [1usize, 4] {
                let rel = (b[k] - gy) as f32;
                if rel < 0.0 || rel == skip || rel > STEP {
                    continue;
                }
                if out.iter().any(|c| c.to_bits() == rel.to_bits()) {
                    continue;
                }
                out.push(rel);
            }
        }
        out.sort_by(|a, b| a.total_cmp(b));
        out
    }

    /// `PlayerSim.checkSupportingBlock`.
    fn check_supporting_block(&mut self, w: &Snap, mx: f64, mz: f64) {
        if self.on_ground {
            let mut found = self.find_support(w, self.x - HALF_WIDTH, self.z - HALF_WIDTH, self.x + HALF_WIDTH, self.z + HALF_WIDTH);
            if !found && !self.on_ground_no_blocks {
                found = self.find_support(w, self.x - HALF_WIDTH - mx, self.z - HALF_WIDTH - mz, self.x + HALF_WIDTH - mx, self.z + HALF_WIDTH - mz);
            }
            self.supported = found;
            self.on_ground_no_blocks = !found;
        } else {
            self.on_ground_no_blocks = false;
            self.supported = false;
        }
    }

    /// `PlayerSim.findSupport`.
    fn find_support(&mut self, w: &Snap, min_x: f64, min_z: f64, max_x: f64, max_z: f64) -> bool {
        let (min_y, max_y) = (self.y - 1.0e-6, self.y);
        let area = [min_x, min_y, min_z, max_x, max_y, max_z];
        let mut found = false;
        let mut best = f64::MAX;
        for cx in floor_i(min_x)..=floor_i(max_x) {
            for cy in floor_i(min_y) - 1..=floor_i(max_y) {
                for cz in floor_i(min_z)..=floor_i(max_z) {
                    let Some(hit) = w.cell_overlaps(cx, cy, cz, &area) else {
                        return false;
                    };
                    if !hit {
                        continue;
                    }
                    let ddx = cx as f64 + 0.5 - self.x;
                    let ddy = cy as f64 + 0.5 - self.y;
                    let ddz = cz as f64 + 0.5 - self.z;
                    let dist = ddx * ddx + ddy * ddy + ddz * ddz;
                    if dist < best || dist == best && (!found || compare(self.sup_x, self.sup_y, self.sup_z, cx, cy, cz) < 0) {
                        self.sup_x = cx;
                        self.sup_y = cy;
                        self.sup_z = cz;
                        best = dist;
                        found = true;
                    }
                }
            }
        }
        found
    }

    #[inline]
    fn supported_x(&self) -> i32 {
        if self.supported { self.sup_x } else { floor_i(self.x) }
    }

    #[inline]
    fn supported_z(&self) -> i32 {
        if self.supported { self.sup_z } else { floor_i(self.z) }
    }

    #[inline]
    fn legacy_y(&self) -> i32 {
        floor_i(self.y - ON_POS_LEGACY)
    }

    /// `PlayerSim.minor` (LocalPlayer.isHorizontalCollisionMinor).
    fn minor(&self, mx: f64, mz: f64) -> bool {
        let s = msin(self.yaw_rad as f64) as f64;
        let c = mcos(self.yaw_rad as f64) as f64;
        let gx = self.in_x * c - self.in_z * s;
        let gz = self.in_z * c + self.in_x * s;
        let a = gx * gx + gz * gz;
        let m = mx * mx + mz * mz;
        if a < 1.0e-5f32 as f64 || m < 1.0e-5f32 as f64 {
            return false;
        }
        let cs = (gx * mx + gz * mz) / (a * m).sqrt();
        cs > tables().minor_cos && cs <= 1.0
    }

    /// `PlayerSim.speedFactor`.
    fn speed_factor(&self, w: &Snap) -> f32 {
        let (cx, cy, cz) = (floor_i(self.x), floor_i(self.y), floor_i(self.z));
        let mut f = w.speed_factor(cx, cy, cz);
        if f == 1.0 && !w.water(cx, cy, cz) {
            f = w.speed_factor(self.supported_x(), floor_i(self.y - BELOW_AFFECTING), self.supported_z());
        }
        if f == 1.0 { f } else { f + self.movement_efficiency as f32 * (1.0 - f) }
    }

    /// `PlayerSim.jumpFactor`.
    fn jump_factor(&self, w: &Snap) -> f32 {
        let here = w.jump_factor(floor_i(self.x), floor_i(self.y), floor_i(self.z));
        let below = w.jump_factor(self.supported_x(), floor_i(self.y - BELOW_AFFECTING), self.supported_z());
        if here == 1.0 { below } else { here }
    }

    /// `PlayerSim.honeySlide`.
    fn honey_slide(&mut self, w: &Snap) {
        if self.on_ground {
            return;
        }
        let old_vy = self.vy / VERTICAL_DRAG as f64 + 0.08;
        if old_vy >= -0.08 {
            return;
        }
        let (dx, dy, dz) = (self.x - self.from_x, self.y - self.from_y, self.z - self.from_z);
        if dx * dx + dy * dy + dz * dz > 0.0 {
            let (px, py, pz) = (self.from_x, self.from_y + dy, self.from_z);
            let mut hit = dy != 0.0 && self.honey_leg(w, px, py, pz, 0.0, dy, 0.0, old_vy);
            if !hit && self.z_first {
                hit = dz != 0.0 && self.honey_leg(w, px, py, pz + dz, 0.0, 0.0, dz, old_vy);
                let _ = hit || dx != 0.0 && self.honey_leg(w, px + dx, py, pz + dz, dx, 0.0, 0.0, old_vy);
            } else if !hit {
                hit = dx != 0.0 && self.honey_leg(w, px + dx, py, pz, dx, 0.0, 0.0, old_vy);
                let _ = hit || dz != 0.0 && self.honey_leg(w, px + dx, py, pz + dz, 0.0, 0.0, dz, old_vy);
            }
        } else {
            let (x, y, z) = (self.x, self.y, self.z);
            self.honey_leg(w, x, y, z, 0.0, 0.0, 0.0, old_vy);
        }
    }

    /// `PlayerSim.honeyLeg`.
    #[allow(clippy::too_many_arguments)]
    fn honey_leg(&mut self, w: &Snap, tx: f64, ty: f64, tz: f64, mx: f64, my: f64, mz: f64, old_vy: f64) -> bool {
        let e = 1.0e-5f32 as f64;
        let (mut min_x, mut max_x) = (tx - HALF_WIDTH + e, tx + HALF_WIDTH - e);
        let (mut min_y, mut max_y) = (ty + e, ty + HEIGHT - e);
        let (mut min_z, mut max_z) = (tz - HALF_WIDTH + e, tz + HALF_WIDTH - e);
        if mx * mx + my * my + mz * mz >= (1.0e-5f32 * 1.0e-5f32) as f64 {
            min_x = jmin(min_x, min_x - mx);
            max_x = jmax(max_x, max_x - mx);
            min_y = jmin(min_y, min_y - my);
            max_y = jmax(max_y, max_y - my);
            min_z = jmin(min_z, min_z - mz);
            max_z = jmax(max_z, max_z - mz);
        }
        for cx in floor_i(min_x)..=floor_i(max_x) {
            for cz in floor_i(min_z)..=floor_i(max_z) {
                if jabs(cx as f64 + 0.5 - self.x) + 1e-7 <= HONEY_REACH && jabs(cz as f64 + 0.5 - self.z) + 1e-7 <= HONEY_REACH {
                    continue;
                }
                for cy in floor_i(min_y)..=floor_i(max_y) {
                    if self.y > cy as f64 + HONEY_TOP - 1e-7 || !w.sticky(cx, cy, cz) {
                        continue;
                    }
                    if old_vy < -0.13 {
                        let k = -0.05 / old_vy;
                        self.vx *= k;
                        self.vz *= k;
                    }
                    self.vy = (-0.05 - 0.08) * VERTICAL_DRAG as f64;
                    return true;
                }
            }
        }
        false
    }
}

/// `Vec3i.compareTo`: y, then z, then x.
fn compare(ax: i32, ay: i32, az: i32, bx: i32, by: i32, bz: i32) -> i32 {
    if ay != by {
        return if ay < by { -1 } else { 1 };
    }
    if az != bz {
        return if az < bz { -1 } else { 1 };
    }
    if ax < bx { -1 } else if ax > bx { 1 } else { 0 }
}

/// `PlayerSim.free` (CollisionGetter.noCollision for the player's box).
fn free(w: &Snap, buf: &mut Vec<Box6>, px: f64, py: f64, pz: f64) -> bool {
    let (min_x, max_x, min_y, max_y) = (px - HALF_WIDTH, px + HALF_WIDTH, py, py + HEIGHT);
    let (min_z, max_z) = (pz - HALF_WIDTH, pz + HALF_WIDTH);
    buf.clear();
    w.collect(min_x, min_y, min_z, max_x, max_y, max_z, buf);
    !buf.iter().any(|b| b[0] < max_x && b[3] > min_x && b[1] < max_y && b[4] > min_y && b[2] < max_z && b[5] > min_z)
}

/// `PlayerSim.liquidIn` (Level.containsAnyLiquid).
fn liquid_in(w: &Snap, px: f64, py: f64, pz: f64) -> bool {
    let (x0, x1) = (floor_i(px - HALF_WIDTH), ceil_i(px + HALF_WIDTH) - 1);
    let (y0, y1) = (floor_i(py), ceil_i(py + HEIGHT) - 1);
    let (z0, z1) = (floor_i(pz - HALF_WIDTH), ceil_i(pz + HALF_WIDTH) - 1);
    for cx in x0..=x1 {
        for cy in y0..=y1 {
            for cz in z0..=z1 {
                if w.water(cx, cy, cz) {
                    return true;
                }
            }
        }
    }
    false
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
