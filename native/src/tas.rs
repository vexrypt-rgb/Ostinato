//! `TasBrute.search`: beam search over per-tick inputs in [`Sim`]. A port of
//! `baritone.pathing.kinematic.TasBrute`; the beam is ordered by (score, state key), as the Java code orders it.

use crate::jmath::{dcompare, floor_i, jmax, jmin, round_d, round_f, UNSUPPORTED};
use std::sync::atomic::{AtomicBool, Ordering::Relaxed};
use crate::sim::{node_height, Box6, Sim, Snap, HALF_WIDTH, HEIGHT};
use std::cmp::Ordering;
use std::collections::hash_map::Entry;
use std::collections::{BinaryHeap, HashMap};
use std::hash::{BuildHasherDefault, Hasher};

pub struct Params<'a> {
    pub yaw0: f32,
    pub beam: i32,
    pub max_ticks: i32,
    pub max_turn: f32,
    pub allow_back: bool,
    pub floor_y: f64,
    pub gx: i32,
    pub gy: i32,
    pub gz: i32,
    pub grab: bool,
    pub turns: &'a [f32],
    /// Threads expanding a layer; the answer does not depend on it.
    pub threads: usize,
}

pub struct Plan {
    pub yaw: Vec<f32>,
    pub input: Vec<i32>,
    pub jump: Vec<bool>,
    pub end: Sim,
}

pub enum Outcome {
    Found(Plan),
    None,
    OutOfRegion,
    TooBig,
}

#[derive(Clone, Copy)]
struct Node {
    s: Sim,
    yaw: f32,
    parent: u32,
    input: i8,
    jump: bool,
    flew: bool,
    depth: u16,
    score: f64,
    key: i64,
}

const NO_PARENT: u32 = u32::MAX;

/// Keys are already a polynomial mix of the state; one multiply spreads them over the table.
#[derive(Default)]
struct KeyHasher(u64);

impl Hasher for KeyHasher {
    fn finish(&self) -> u64 {
        self.0
    }
    fn write(&mut self, _: &[u8]) {
        unreachable!()
    }
    fn write_i64(&mut self, k: i64) {
        self.0 = (k as u64).wrapping_mul(0x9E37_79B9_7F4A_7C15).rotate_left(26);
    }
}

type KeyMap = HashMap<i64, u32, BuildHasherDefault<KeyHasher>>;

struct Goal {
    x: i32,
    y: i32,
    z: i32,
    grab: bool,
}

impl Goal {
    fn reached(&self, s: &Sim, w: &Snap, buf: &mut Vec<Box6>) -> bool {
        if self.grab {
            floor_i(s.x) == self.x && floor_i(s.y) == self.y && floor_i(s.z) == self.z && s.climbable(w)
        } else {
            self.on(s, w, buf)
        }
    }

    fn on(&self, s: &Sim, w: &Snap, buf: &mut Vec<Box6>) -> bool {
        if !s.on_ground || !node_height(s.y, self.y) {
            return false;
        }
        buf.clear();
        let h = HALF_WIDTH - 1e-4;
        w.collect(s.x - h, s.y - 0.01, s.z - h, s.x + h, s.y, s.z + h, buf);
        let mut any = false;
        for b in buf.iter() {
            if b[4] < s.y - 0.01 || b[1] > s.y || b[3] <= s.x - h || b[0] >= s.x + h || b[5] <= s.z - h || b[2] >= s.z + h {
                continue;
            }
            if floor_i(b[0] + 1e-6) != self.x || floor_i(b[2] + 1e-6) != self.z {
                return false;
            }
            any = true;
        }
        any
    }
}

const RES: f64 = 0.1;
/// A field this large (cells) means a landing far from the start; leave it to Java.
const FIELD_LIMIT: i64 = 4_000_000;

struct Field {
    x0: f64,
    z0: f64,
    nx: i32,
    nz: i32,
    dist: Vec<f64>,
}

#[derive(PartialEq)]
struct Open(f64, i32, i32);

impl Eq for Open {}

impl PartialOrd for Open {
    fn partial_cmp(&self, o: &Self) -> Option<Ordering> {
        Some(self.cmp(o))
    }
}

impl Ord for Open {
    // a min-heap on distance; the order among equal distances cannot change the distances found
    fn cmp(&self, o: &Self) -> Ordering {
        dcompare(o.0, self.0)
    }
}

impl Field {
    fn new(w: &Snap, buf: &mut Vec<Box6>, start: &Sim, g: &Goal) -> Option<Field> {
        let min_x = jmin(start.x, g.x as f64) - 4.0;
        let max_x = jmax(start.x, g.x.wrapping_add(1) as f64) + 4.0;
        let min_z = jmin(start.z, g.z as f64) - 4.0;
        let max_z = jmax(start.z, g.z.wrapping_add(1) as f64) + 4.0;
        let nx = ((max_x - min_x) / RES).ceil() as i32;
        let nz = ((max_z - min_z) / RES).ceil() as i32;
        if nx < 0 || nz < 0 || nx as i64 * nz as i64 > FIELD_LIMIT {
            return None;
        }
        let (x0, z0) = (min_x, min_z);
        let n = (nx * nz) as usize;
        let mut dist = vec![f64::INFINITY; n];
        let mut free = vec![false; n];
        let y = jmax(start.y, g.y.wrapping_sub(1) as f64 + 0.01) + 0.05;
        buf.clear();
        w.collect(min_x - 1.0, y, min_z - 1.0, max_x + 1.0, y + HEIGHT - 0.1, max_z + 1.0, buf);
        let h = HALF_WIDTH;
        let top = y + HEIGHT - 0.1;
        // only the boxes the standing box at this height can touch; the order of the test does not matter
        let tall: Vec<Box6> = buf.iter().filter(|b| b[4] > y && b[1] < top).copied().collect();
        for i in 0..nx {
            let cx = x0 + (i as f64 + 0.5) * RES;
            for k in 0..nz {
                let cz = z0 + (k as f64 + 0.5) * RES;
                free[(i * nz + k) as usize] = !tall.iter().any(|b| b[3] > cx - h && b[0] < cx + h && b[5] > cz - h && b[2] < cz + h);
            }
        }
        let mut open = BinaryHeap::new();
        for i in 0..nx {
            for k in 0..nz {
                let cx = x0 + (i as f64 + 0.5) * RES;
                let cz = z0 + (k as f64 + 0.5) * RES;
                if floor_i(cx) == g.x && floor_i(cz) == g.z {
                    dist[(i * nz + k) as usize] = 0.0;
                    open.push(Open(0.0, i, k));
                }
            }
        }
        let diag = RES * std::f64::consts::SQRT_2;
        while let Some(Open(d, i, k)) = open.pop() {
            if d > dist[(i * nz + k) as usize] {
                continue;
            }
            for di in -1..=1 {
                for dk in -1..=1 {
                    let (a, b) = (i + di, k + dk);
                    if (di | dk) == 0 || a < 0 || b < 0 || a >= nx || b >= nz || !free[(a * nz + b) as usize] {
                        continue;
                    }
                    let nd = d + if di != 0 && dk != 0 { diag } else { RES * 1.0 };
                    let j = (a * nz + b) as usize;
                    if nd < dist[j] {
                        dist[j] = nd;
                        open.push(Open(nd, a, b));
                    }
                }
            }
        }
        Some(Field { x0, z0, nx, nz, dist })
    }

    #[inline]
    fn at(&self, x: f64, z: f64) -> f64 {
        let i = ((x - self.x0) / RES).floor() as i32;
        let k = ((z - self.z0) / RES).floor() as i32;
        if i < 0 || k < 0 || i >= self.nx || k >= self.nz {
            return f64::INFINITY;
        }
        self.dist[(i * self.nz + k) as usize]
    }
}

#[inline]
fn score(f: &Field, s: &Sim, ty: i32) -> f64 {
    let mut d = f.at(s.x + s.vx * 4.0, s.z + s.vz * 4.0);
    if d.is_infinite() {
        d = f.at(s.x, s.z) + 0.5;
    }
    let below = jmax(0.0, ty as f64 - s.y);
    d + below * 0.5
}

#[inline]
fn key(s: &Sim, yaw: f32) -> i64 {
    let m = |h: i64, v: i64| h.wrapping_mul(1_000_003).wrapping_add(v);
    let mut h = round_d(s.x * 32.0);
    h = m(h, round_d(s.y * 32.0));
    h = m(h, round_d(s.z * 32.0));
    h = m(h, round_d(s.vx * 64.0));
    h = m(h, round_d(s.vy * 64.0));
    h = m(h, round_d(s.vz * 64.0));
    h = h.wrapping_mul(31).wrapping_add(round_f(yaw / 15.0) as i64);
    h.wrapping_mul(31).wrapping_add((s.on_ground as i64) + if s.sprinting { 2 } else { 0 })
}

#[inline]
fn order(a: &Node, b: &Node) -> Ordering {
    dcompare(a.score, b.score).then(a.key.cmp(&b.key))
}

/// Keeps the `k` first of `v` in [`order`], sorted.
fn best(v: &mut Vec<Node>, k: usize) {
    if k < v.len() {
        if k > 0 {
            v.select_nth_unstable_by(k - 1, order);
        }
        v.truncate(k);
    }
    v.sort_unstable_by(order);
}

fn holds(w: &Snap, buf: &mut Vec<Box6>, landed: &Sim, yaw: f32, g: &Goal) -> bool {
    let mut c = *landed;
    for _ in 0..3 {
        c.tick(w, buf, yaw, 0, 0, false, false);
    }
    if g.grab { c.climbable(w) } else { g.on(&c, w, buf) }
}

fn trace(arena: &[Node], end: &Node) -> Plan {
    let n = end.depth as usize;
    let mut plan = Plan { yaw: vec![0.0; n], input: vec![0; n], jump: vec![false; n], end: end.s };
    let mut k = *end;
    while k.parent != NO_PARENT {
        let d = k.depth as usize - 1;
        plan.yaw[d] = k.yaw;
        plan.input[d] = k.input as i32;
        plan.jump[d] = k.jump;
        k = arena[k.parent as usize];
    }
    plan
}

/// What one layer node's expansion needs, shared read-only by the threads.
struct Ctx<'a, 'w> {
    w: &'a Snap<'w>,
    field: &'a Field,
    g: &'a Goal,
    p: &'a Params<'a>,
    inputs: &'a [i32],
    floor: f64,
    fall_line: f64,
}

/// A child state for the merge: `found` ends the search there.
#[derive(Clone, Copy)]
struct Child {
    n: Node,
    found: bool,
}

/// Every child of `nodes`, in the Java loop's order (node, turn, input, jump), minus the pruned ones; stops after the
/// first that lands, since nothing after it can matter.
fn expand(c: &Ctx, arena: &[Node], nodes: &[u32], t: i32, out: &mut Vec<Child>, buf: &mut Vec<Box6>) {
    out.clear();
    for &ni in nodes {
        let n = arena[ni as usize];
        for &tf in c.p.turns {
            let yaw = n.yaw + tf * c.p.max_turn;
            for &inp in c.inputs {
                for j in 0..(if n.s.on_ground { 2 } else { 1 }) {
                    let jump = j == 1;
                    let mut s = n.s;
                    s.tick(c.w, buf, yaw, inp, 0, inp > 0, jump);
                    let flew = n.flew || !s.on_ground;
                    let mut m = Node { s, yaw, parent: ni, input: inp as i8, jump, flew, depth: (t + 1) as u16, score: 0.0, key: 0 };
                    if (flew || c.g.grab) && c.g.reached(&s, c.w, buf) && holds(c.w, buf, &s, yaw, c.g) {
                        out.push(Child { n: m, found: true });
                        return;
                    }
                    if s.y < c.floor || !s.on_ground && s.vy < 0.0 && s.y < c.fall_line && flew {
                        continue;
                    }
                    m.score = score(c.field, &s, c.g.y);
                    m.key = key(&s, yaw);
                    out.push(Child { n: m, found: false });
                }
            }
        }
    }
}

/// Fewer layer nodes than this per thread are not worth a thread.
const MIN_PER_THREAD: usize = 48;

/// `TasBrute.search(start, yaw0, goal)`.
pub fn search(w: &Snap, start: &Sim, p: &Params) -> Outcome {
    let g = Goal { x: p.gx, y: p.gy, z: p.gz, grab: p.grab };
    let mut buf: Vec<Box6> = Vec::with_capacity(64);
    let field = match Field::new(w, &mut buf, start, &g) {
        Some(f) => f,
        None => return Outcome::TooBig,
    };
    if w.missed() {
        return Outcome::OutOfRegion;
    }
    let inputs: &[i32] = if p.allow_back { &[1, 0, -1] } else { &[1, 0] };
    let ctx = Ctx {
        w,
        field: &field,
        g: &g,
        p,
        inputs,
        floor: jmax(p.floor_y, jmin(start.y, g.y.wrapping_sub(1) as f64) - 1.0),
        fall_line: g.y as f64 - if g.grab { 0.0 } else { 0.75 } - 1e-3,
    };
    let beam = p.beam.max(0) as usize;
    let threads = p.threads.max(1);
    let mut arena: Vec<Node> = Vec::with_capacity(beam.min(1 << 16) * 4);
    arena.push(Node { s: *start, yaw: p.yaw0, parent: NO_PARENT, input: 0, jump: false, flew: false, depth: 0, score: 0.0, key: 0 });
    let mut layer: Vec<u32> = vec![0];
    let mut outs: Vec<Vec<Child>> = (0..threads).map(|_| Vec::new()).collect();
    let mut bufs: Vec<Vec<Box6>> = (0..threads).map(|_| Vec::with_capacity(64)).collect();
    let mut cand: Vec<Node> = Vec::new();
    let mut map: KeyMap = KeyMap::default();
    let mut ground: Vec<Node> = Vec::new();
    let mut air: Vec<Node> = Vec::new();
    let unsupported = AtomicBool::new(false);
    for t in 0..p.max_ticks {
        let parts = threads.min(layer.len().div_ceil(MIN_PER_THREAD)).max(1);
        let chunk = layer.len().div_ceil(parts);
        if parts == 1 {
            expand(&ctx, &arena, &layer, t, &mut outs[0], &mut bufs[0]);
        } else {
            let (arena_ref, ctx_ref, unsup) = (&arena, &ctx, &unsupported);
            std::thread::scope(|sc| {
                for ((nodes, out), b) in layer.chunks(chunk).zip(outs.iter_mut()).zip(bufs.iter_mut()) {
                    sc.spawn(move || {
                        expand(ctx_ref, arena_ref, nodes, t, out, b);
                        // fdlibm's out-of-range flag lives per thread
                        if UNSUPPORTED.with(|u| u.get()) {
                            unsup.store(true, Relaxed);
                        }
                    });
                }
            });
        }
        if w.missed() {
            return Outcome::OutOfRegion;
        }
        if unsupported.load(Relaxed) {
            UNSUPPORTED.with(|u| u.set(true));
            return Outcome::None;
        }
        // merge in the single-threaded order: the same first landing, the same survivor of each key
        let used = layer.chunks(chunk).count();
        cand.clear();
        map.clear();
        for out in outs.iter().take(used) {
            for ch in out {
                if ch.found {
                    return Outcome::Found(trace(&arena, &ch.n));
                }
                let m = ch.n;
                match map.entry(m.key) {
                    Entry::Vacant(e) => {
                        e.insert(cand.len() as u32);
                        cand.push(m);
                    }
                    Entry::Occupied(e) => {
                        let old = &mut cand[*e.get() as usize];
                        if m.score < old.score {
                            *old = m;
                        }
                    }
                }
            }
        }
        ground.clear();
        air.clear();
        for n in &cand {
            if n.s.on_ground { ground.push(*n) } else { air.push(*n) }
        }
        let gk = ground.len().min((beam / 2).max(beam.saturating_sub(air.len())));
        let ak = air.len().min(beam - gk);
        // the order is total (keys are unique), so the best k found by selection are the ones a full sort puts first
        best(&mut ground, gk);
        best(&mut air, ak);
        let mut next: Vec<Node> = Vec::with_capacity(gk + ak);
        let (mut i, mut j) = (0, 0);
        while i < ground.len() || j < air.len() {
            if j == air.len() || i < ground.len() && order(&ground[i], &air[j]) == Ordering::Less {
                next.push(ground[i]);
                i += 1;
            } else {
                next.push(air[j]);
                j += 1;
            }
        }
        if next.is_empty() {
            return Outcome::None;
        }
        layer.clear();
        for n in next {
            layer.push(arena.len() as u32);
            arena.push(n);
        }
    }
    Outcome::None
}
