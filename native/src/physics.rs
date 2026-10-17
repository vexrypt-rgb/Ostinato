//! `PhysicsPathfinder.plan`: A* over simulated player states, each edge one of 49 inputs held for two ticks. A port of
//! `baritone.pathing.physics.PhysicsPathfinder`; the open set is a copy of `java.util.PriorityQueue`'s binary heap, so
//! nodes with equal f leave it in the order they leave the Java one.

use crate::jmath::{dcompare, jmax};
use crate::sim::{Box6, Sim, Snap};
use std::cmp::Ordering;
use std::collections::HashMap;

pub const HOLD: i32 = 2;
const MAX_SPEED: f64 = 0.29;
const JUMP_COST: i32 = 4;

/// One held input: yaw, forward, sprint, jump.
#[derive(Clone, Copy)]
pub struct Action {
    pub yaw: f32,
    pub forward: bool,
    pub sprint: bool,
    pub jump: bool,
}

/// `PhysicsPathfinder.ACTIONS`, in its order.
pub fn actions() -> Vec<Action> {
    let mut a = Vec::with_capacity(49);
    for i in 0..16 {
        let yaw = i as f32 * 22.5f32;
        a.push(Action { yaw, forward: true, sprint: true, jump: false });
        a.push(Action { yaw, forward: true, sprint: true, jump: true });
        a.push(Action { yaw, forward: true, sprint: false, jump: false });
    }
    a.push(Action { yaw: 0.0, forward: false, sprint: false, jump: false });
    a
}

struct Node {
    sim: Sim,
    parent: u32,
    action: u8,
    ticks: i32,
    f: f64,
}

pub enum Outcome {
    /// Indices into [`actions`], one per held step (each held [`HOLD`] ticks).
    Found(Vec<u8>),
    None,
    OutOfRegion,
}

fn sq(v: f64) -> f64 {
    v * v
}

fn h(s: &Sim, gx: f64, gy: f64, gz: f64) -> f64 {
    (sq(s.x - gx) + sq(s.z - gz)).sqrt() / MAX_SPEED + jmax(0.0, gy - s.y) * 4.0
}

fn speed_bucket(v: f64) -> i64 {
    0i64.max(15i64.min((v * 10.0).floor() as i64 + 8))
}

fn key(s: &Sim) -> i64 {
    let mut k = (s.x * 4.0).floor() as i64 & 0xFFFFF;
    k = k << 20 | ((s.z * 4.0).floor() as i64 & 0xFFFFF);
    k = k << 12 | ((s.y * 4.0).floor() as i64 & 0xFFF);
    k = k << 4 | speed_bucket(s.vx);
    k = k << 4 | speed_bucket(s.vz);
    k << 1 | if s.on_ground { 1 } else { 0 }
}

/// `java.util.PriorityQueue` over node indices, ordered by `Double.compare` of f.
struct Heap {
    q: Vec<u32>,
}

impl Heap {
    fn cmp(nodes: &[Node], a: u32, b: u32) -> Ordering {
        dcompare(nodes[a as usize].f, nodes[b as usize].f)
    }

    fn add(&mut self, nodes: &[Node], e: u32) {
        let mut k = self.q.len();
        self.q.push(e);
        while k > 0 {
            let parent = (k - 1) >> 1;
            let p = self.q[parent];
            if Self::cmp(nodes, e, p) != Ordering::Less {
                break;
            }
            self.q[k] = p;
            k = parent;
        }
        self.q[k] = e;
    }

    fn poll(&mut self, nodes: &[Node]) -> Option<u32> {
        if self.q.is_empty() {
            return None;
        }
        let result = self.q[0];
        let x = self.q.pop().unwrap();
        let n = self.q.len();
        if n > 0 {
            let mut k = 0;
            let half = n >> 1;
            while k < half {
                let mut child = 2 * k + 1;
                let mut c = self.q[child];
                let right = child + 1;
                if right < n && Self::cmp(nodes, c, self.q[right]) == Ordering::Greater {
                    child = right;
                    c = self.q[child];
                }
                if Self::cmp(nodes, x, c) != Ordering::Greater {
                    break;
                }
                self.q[k] = c;
                k = child;
            }
            self.q[k] = x;
        }
        Some(result)
    }
}

const NO_PARENT: u32 = u32::MAX;

/// Plans from `start` until standing within `radius` of the goal, expanding at most `max_nodes` nodes.
pub fn plan(w: &Snap, start: &Sim, gx: f64, gy: f64, gz: f64, radius: f64, max_nodes: i32) -> Outcome {
    let acts = actions();
    let mut nodes: Vec<Node> = Vec::with_capacity(max_nodes.max(0) as usize * 8 + 1);
    let mut best: HashMap<i64, i32> = HashMap::with_capacity(max_nodes.max(0) as usize * 16);
    let mut open = Heap { q: Vec::with_capacity(1024) };
    let mut buf: Vec<Box6> = Vec::with_capacity(64);
    nodes.push(Node { sim: start.clone(), parent: NO_PARENT, action: 0, ticks: 0, f: h(start, gx, gy, gz) });
    open.add(&nodes, 0);
    let mut expanded = 0;
    while !open.q.is_empty() && {
        let go = expanded < max_nodes;
        expanded += 1;
        go
    } {
        let n = open.poll(&nodes).unwrap();
        let s = nodes[n as usize].sim.clone();
        if s.on_ground && sq(s.x - gx) + sq(s.y - gy) + sq(s.z - gz) <= radius * radius {
            let mut out = Vec::new();
            let mut i = n;
            while nodes[i as usize].parent != NO_PARENT {
                out.push(nodes[i as usize].action);
                i = nodes[i as usize].parent;
            }
            out.reverse();
            return Outcome::Found(out);
        }
        let ticks = nodes[n as usize].ticks;
        for (ai, a) in acts.iter().enumerate() {
            let mut c = s.clone();
            for _ in 0..HOLD {
                c.tick(w, &mut buf, a.yaw, if a.forward { 1 } else { 0 }, 0, a.sprint, a.jump);
            }
            if w.missed() {
                return Outcome::OutOfRegion;
            }
            if c.y < gy - 64.0 {
                continue;
            }
            let g = ticks + HOLD + if a.jump && s.on_ground { JUMP_COST } else { 0 };
            let k = key(&c);
            match best.get(&k) {
                Some(&seen) => {
                    if seen <= g {
                        continue;
                    }
                    best.insert(k, g);
                }
                None => {
                    best.insert(k, g);
                }
            }
            let f = g as f64 + h(&c, gx, gy, gz);
            nodes.push(Node { sim: c, parent: n, action: ai as u8, ticks: g, f });
            open.add(&nodes, (nodes.len() - 1) as u32);
        }
    }
    Outcome::None
}
