//! `JumpSearch.search` over the full grid (a grid of open-loop jump plans flown in [`Sim`]) and `MomentumHop.find` (a
//! depth-first search over which ground ticks to hop on). Ports of `baritone.pathing.kinematic.JumpSearch` and
//! `MomentumHop`, operation for operation.

use crate::jmath::{floor_i, jabs, jmax, jmin, round_d, UNSUPPORTED};
use crate::sim::{feet_cell, node_height, Box6, Sim, Snap, HALF_WIDTH};
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};

pub const O0: [f32; 6] = [-15.0, 0.0, 15.0, 30.0, 45.0, 60.0];
pub const HOP: [i32; 2] = [0, 1];
pub const EDGE: [f64; 6] = [-0.5, -0.3, -0.1, 0.1, 0.25, 0.4];
pub const PLAIN_EDGES: usize = 5;
pub const O1: [f32; 9] = [-70.0, -45.0, -25.0, -10.0, 0.0, 10.0, 25.0, 45.0, 70.0];
pub const SWITCH: [i32; 2] = [0, 4];
pub const NEVER: i32 = 999;
pub const RELEASE: [i32; 4] = [NEVER, 4, 7, 10];
pub const BRAKE: [i32; 2] = [0, 1];
pub const DIMS: usize = 7;
const MAX_GROUND: i32 = 40;
const MAX_AIR: i32 = 40;
const SETTLE: i32 = 8;
const HANG: i32 = 3;
const YAW_STEP: f32 = 32.0;
/// Fewer plans than this per thread are not worth a thread.
const MIN_PER_THREAD: usize = 256;

/// A `JumpSearch`'s fields: the frame, the flags, the plan and the jump state, and what a run leaves behind.
pub struct JumpSearch<'a> {
    pub w: &'a Snap<'a>,
    /// `approachYaw()`, computed by the caller (`Math.atan2` is not pinned to one result in Java).
    pub approach_yaw: f32,
    pub dir_x: f64,
    pub dir_z: f64,
    pub edge: f64,
    pub dest_x: i32,
    pub dest_y: i32,
    pub dest_z: i32,
    pub side: i32,
    pub bounce_y: i32,
    pub any_dest: bool,
    pub no_jump: bool,
    pub grab: bool,
    pub stand_jump: bool,
    pub carry: bool,
    pub ledge: bool,
    pub plan: [i32; DIMS],
    pub jumped: bool,
    pub air_ticks: i32,
    pub slew_yaw: bool,
    pub face_yaw: f32,
    pub ticks: i32,
    pub miss: f64,
    pub land_speed: f64,
    pub sim: Sim,
    buf: Vec<Box6>,
}

impl<'a> JumpSearch<'a> {
    pub fn new(w: &'a Snap<'a>) -> Self {
        JumpSearch {
            w,
            approach_yaw: 0.0,
            dir_x: 0.0,
            dir_z: 0.0,
            edge: 0.0,
            dest_x: 0,
            dest_y: 0,
            dest_z: 0,
            side: 1,
            bounce_y: i32::MAX,
            any_dest: false,
            no_jump: false,
            grab: false,
            stand_jump: false,
            carry: false,
            ledge: false,
            plan: [0; DIMS],
            jumped: false,
            air_ticks: 0,
            slew_yaw: false,
            face_yaw: 0.0,
            ticks: 0,
            miss: 0.0,
            land_speed: 0.0,
            sim: Sim::default(),
            buf: Vec::with_capacity(64),
        }
    }

    fn yaw(&self, p: &[i32; DIMS], jumped: bool, air: i32) -> f32 {
        let o = if jumped && air >= SWITCH[p[4] as usize] { O1[p[3] as usize] } else { O0[p[0] as usize] };
        self.approach_yaw + self.side as f32 * o
    }

    fn input(&self, p: &[i32; DIMS], jumped: bool, air: i32) -> i32 {
        if !jumped || air < RELEASE[p[5] as usize] { 1 } else { -BRAKE[p[6] as usize] }
    }

    fn jump(&self, p: &[i32; DIMS], jumped: bool) -> bool {
        let s = &self.sim;
        if jumped || !s.on_ground || self.no_jump {
            return false;
        }
        if self.stand_jump {
            return true;
        }
        let along = s.x * self.dir_x + s.z * self.dir_z;
        along >= self.edge + EDGE[p[2] as usize] || (HOP[p[1] as usize] == 1 && along < self.edge + EDGE[p[2] as usize] - 1.2)
    }

    /// `JumpSearch.stepYaw`.
    pub fn step_yaw(from: f32, to: f32) -> f32 {
        let mut d = to - from;
        d %= 360.0f32;
        if d >= 180.0f32 {
            d -= 360.0f32;
        }
        if d < -180.0f32 {
            d += 360.0f32;
        }
        if d > YAW_STEP {
            d = YAW_STEP;
        } else if d < -YAW_STEP {
            d = -YAW_STEP;
        }
        from + d
    }

    /// `JumpSearch.run` without a sweep.
    pub fn run(&mut self, start: &Sim, p: &[i32; DIMS], jumped0: bool, air0: i32) -> bool {
        self.sim = *start;
        let mut jumped = jumped0;
        let mut air = air0;
        let mut settle = -1;
        let mut bounced = false;
        let mut facing = self.face_yaw;
        let floor = floor_i(start.y + 0.01).min(self.dest_y).min(self.bounce_y) as f64 - 0.6;
        for t in 0..(MAX_GROUND + MAX_AIR + SETTLE) {
            if !jumped && t >= MAX_GROUND {
                return false;
            }
            let j = self.jump(p, jumped);
            let takeoff = j && (self.stand_jump || self.sim.x * self.dir_x + self.sim.z * self.dir_z >= self.edge + EDGE[p[2] as usize]);
            let inp = if settle < 0 { self.input(p, jumped, air) } else { 0 };
            let mut cmd = self.yaw(p, jumped, air);
            if self.slew_yaw {
                facing = Self::step_yaw(facing, cmd);
                cmd = facing;
            }
            self.sim.tick(self.w, &mut self.buf, cmd, inp, 0, inp > 0, j);
            if takeoff
                || (!jumped && !self.sim.on_ground && self.bounce_y != i32::MAX && self.sim.x * self.dir_x + self.sim.z * self.dir_z > self.edge)
            {
                jumped = true;
                air = 0;
            } else if jumped {
                air += 1;
            }
            let s = self.sim;
            if s.y < floor {
                return false;
            }
            if self.grab && jumped && air >= 1 && floor_i(s.x) == self.dest_x && floor_i(s.y) == self.dest_y && floor_i(s.z) == self.dest_z
                && s.climbable(self.w)
            {
                return self.holds(t + 1);
            }
            if s.on_ground && s.vy > 0.1 {
                bounced = true;
            }
            if jumped && air > 1 && s.on_ground && settle < 0 && s.vy <= 0.1 {
                if self.any_dest {
                    if !bounced {
                        return false;
                    }
                    self.dest_x = floor_i(s.x);
                    self.dest_y = feet_cell(s.y);
                    self.dest_z = floor_i(s.z);
                }
                if !node_height(s.y, self.dest_y) {
                    return false;
                }
                if self.carry {
                    if floor_i(s.x) != self.dest_x || floor_i(s.z) != self.dest_z {
                        return false;
                    }
                    self.ticks = t + 1;
                    self.miss = jmax(jabs(s.x - (self.dest_x as f64 + 0.5)), jabs(s.z - (self.dest_z as f64 + 0.5)));
                    self.land_speed = s.vx * self.dir_x + s.vz * self.dir_z;
                    return true;
                }
                settle = 0;
            }
            if settle >= 0 {
                let old = settle;
                settle += 1;
                if old >= SETTLE {
                    let cx = s.x - (self.dest_x as f64 + 0.5);
                    let cz = s.z - (self.dest_z as f64 + 0.5);
                    let reach = 0.5 + HALF_WIDTH - 1e-4;
                    if jabs(cx) > reach || jabs(cz) > reach || !s.on_ground {
                        return false;
                    }
                    self.ticks = t + 1;
                    self.miss = jmax(jabs(cx), jabs(cz));
                    return true;
                }
            }
        }
        false
    }

    fn holds(&mut self, tick: i32) -> bool {
        for _ in 0..HANG {
            self.sim.tick(self.w, &mut self.buf, 0.0, 0, 0, false, false);
            if !self.sim.climbable(self.w) {
                return false;
            }
        }
        self.ticks = tick;
        self.miss = 0.0;
        true
    }

    /// A search with this one's frame, flags and jump state, for a thread of [`Self::search`].
    fn worker(&self) -> JumpSearch<'a> {
        JumpSearch { sim: self.sim, buf: Vec::with_capacity(64), ..*self }
    }

    /// `JumpSearch.search(start, false)` (the full grid; Java keeps the local one), the plans split over up to `threads` threads. Runs only depend on each other through the
    /// destination an [`Self::any_dest`] run rewrites, so that search stays on one thread. Each thread keeps its first
    /// best plan, and the threads' bests are merged in grid order, so the answer is the single-threaded one.
    pub fn search(&mut self, start: &Sim, threads: usize) -> bool {
        let c = self.plan;
        let size = [O0.len() as i32, HOP.len() as i32, if self.ledge { EDGE.len() } else { PLAIN_EDGES } as i32, O1.len() as i32,
            SWITCH.len() as i32, RELEASE.len() as i32, BRAKE.len() as i32];
        let mut lo = [0i32; DIMS];
        let mut hi = [0i32; DIMS];
        for k in 0..DIMS {
            let fixed = self.jumped && k <= 2;
            lo[k] = if fixed { c[k] } else { 0 };
            hi[k] = if fixed { c[k] } else { size[k] - 1 };
        }
        // the grid in the Java loops' order, the last index fastest
        let mut plans: Vec<[i32; DIMS]> = Vec::new();
        let mut p = lo;
        'grid: loop {
            plans.push(p);
            let mut k = DIMS;
            loop {
                if k == 0 {
                    break 'grid;
                }
                k -= 1;
                if p[k] < hi[k] {
                    p[k] += 1;
                    break;
                }
                p[k] = lo[k];
            }
        }
        let (jumped, air) = (self.jumped, self.air_ticks);
        let parts = if self.any_dest { 1 } else { threads.max(1).min(plans.len().div_ceil(MIN_PER_THREAD)).max(1) };
        let best = if parts == 1 {
            self.best_of(start, &plans, jumped, air)
        } else {
            let chunk = plans.len().div_ceil(parts);
            let unsupported = AtomicBool::new(false);
            let mut bests: Vec<Option<(f64, [i32; DIMS])>> = vec![None; plans.len().div_ceil(chunk)];
            std::thread::scope(|sc| {
                for (part, out) in plans.chunks(chunk).zip(bests.iter_mut()) {
                    let mut js = self.worker();
                    let unsup = &unsupported;
                    sc.spawn(move || {
                        *out = js.best_of(start, part, jumped, air);
                        // fdlibm's out-of-range flag lives per thread
                        if UNSUPPORTED.with(|u| u.get()) {
                            unsup.store(true, Relaxed);
                        }
                    });
                }
            });
            if unsupported.load(Relaxed) {
                UNSUPPORTED.with(|u| u.set(true));
            }
            let mut best: Option<(f64, [i32; DIMS])> = None;
            for (score, plan) in bests.into_iter().flatten() {
                if best.is_none_or(|(b, _)| score < b) {
                    best = Some((score, plan));
                }
            }
            if best.is_none() {
                // a failed search leaves the sim where the grid's last run left it
                let last = plans[plans.len() - 1];
                self.run(start, &last, jumped, air);
            }
            best
        };
        let Some((_, b)) = best else { return false };
        self.plan = b;
        let plan = self.plan;
        self.run(start, &plan, jumped, air);
        true
    }

    /// The first plan of `plans` with the lowest score, as `JumpSearch.search`'s loop keeps it.
    fn best_of(&mut self, start: &Sim, plans: &[[i32; DIMS]], jumped: bool, air: i32) -> Option<(f64, [i32; DIMS])> {
        let mut best: Option<[i32; DIMS]> = None;
        let mut best_score = f64::MAX;
        for p in plans {
            if self.run(start, p, jumped, air) {
                let score = if self.carry { -self.land_speed + 0.004 * self.ticks as f64 } else { self.miss + 0.004 * self.ticks as f64 };
                if score < best_score {
                    best_score = score;
                    best = Some(*p);
                }
            }
        }
        best.map(|b| (best_score, b))
    }
}

pub const HOP_MAX_TICKS: i32 = 60;

/// `MomentumHop.find`: the jump pattern ('J' or '.' per tick) that ends standing on dest, or None.
#[allow(clippy::too_many_arguments)]
pub fn momentum_hop(w: &Snap, start: &Sim, yaw: f32, dir_x: i32, dir_z: i32, dest_x: i32, dest_y: i32, dest_z: i32, jumped_last: bool, budget: i32) -> Option<Vec<u8>> {
    let mut s = Hop {
        w,
        yaw,
        dir_x: dir_x as f64,
        dir_z: dir_z as f64,
        dest_x,
        dest_y,
        dest_z,
        floor_y: jmin(start.y, dest_y as f64) - 0.6,
        beyond: (dest_x as f64 + 0.5 + 0.5 * dir_x as f64) * dir_x as f64 + (dest_z as f64 + 0.5 + 0.5 * dir_z as f64) * dir_z as f64 + 1.0,
        seen: HashSet::new(),
        spent: 0,
        budget,
        buf: Vec::with_capacity(64),
    };
    let mut trail = Vec::new();
    if s.dfs(start, 0, jumped_last, &mut trail) { Some(trail) } else { None }
}

struct Hop<'a> {
    w: &'a Snap<'a>,
    yaw: f32,
    dir_x: f64,
    dir_z: f64,
    dest_x: i32,
    dest_y: i32,
    dest_z: i32,
    floor_y: f64,
    beyond: f64,
    seen: HashSet<i64>,
    spent: i32,
    budget: i32,
    buf: Vec<Box6>,
}

impl Hop<'_> {
    fn on(&self, p: &Sim) -> bool {
        p.on_ground && floor_i(p.x) == self.dest_x && floor_i(p.z) == self.dest_z && feet_cell(p.y) == self.dest_y
    }

    fn dfs(&mut self, p: &Sim, t: i32, prev: bool, trail: &mut Vec<u8>) -> bool {
        if self.on(p) {
            return true;
        }
        if p.y < self.floor_y || t >= HOP_MAX_TICKS || self.spent > self.budget {
            return false;
        }
        if p.x * self.dir_x + p.z * self.dir_z > self.beyond {
            return false;
        }
        let key = ((round_d((p.x * self.dir_x + p.z * self.dir_z) * 200.0) & 0xFFFFF) << 40)
            ^ ((round_d(p.y * 200.0) & 0xFFFF) << 24)
            ^ ((round_d((p.vx * self.dir_x + p.vz * self.dir_z) * 2000.0) & 0xFFF) << 12)
            ^ ((round_d(p.vy * 1000.0) & 0x7FF) << 1)
            ^ if prev { 1 } else { 0 };
        if !self.seen.insert(key) {
            return false;
        }
        let opts: &[bool] = if p.on_ground && !prev { &[true, false] } else { &[false] };
        for &j in opts {
            let mut c = *p;
            c.tick(self.w, &mut self.buf, self.yaw, 1, 0, true, j);
            self.spent += 1;
            trail.push(if j { b'J' } else { b'.' });
            if self.dfs(&c, t + 1, j, trail) {
                return true;
            }
            trail.pop();
        }
        false
    }
}
