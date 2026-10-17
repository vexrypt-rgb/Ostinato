//! ostinato-native: Rust twins of Ostinato's hottest searches, called from Java through the FFM API
//! (`baritone.natives.NativeLib`). Every function here gives exactly the answer its Java original gives; see
//! `docs/RUST_NATIVE.md`.
//!
//! The C ABI takes plain pointers and counts. Every export catches panics and returns an error code instead, and
//! nothing is global, so calls from several threads at once are safe.

pub mod jmath;
pub mod sim;
pub mod tas;

use sim::{Sim, Snap, STATE_LEN};
use std::sync::atomic::AtomicBool;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

/// Bumped whenever an export's signature or meaning changes; Java refuses a library with another version.
pub const ABI_VERSION: i32 = 1;

pub const ERR_NONE: i32 = -1;
pub const ERR_OUT_OF_REGION: i32 = -2;
pub const ERR_BAD_ARGS: i32 = -3;
pub const ERR_UNSUPPORTED: i32 = -4;
pub const ERR_PANIC: i32 = -5;
pub const ERR_TOO_SMALL: i32 = -6;

#[unsafe(no_mangle)]
pub extern "C" fn ost_abi_version() -> i32 {
    ABI_VERSION
}

fn guard(f: impl FnOnce() -> i32) -> i32 {
    jmath::UNSUPPORTED.with(|u| u.set(false));
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(r) if jmath::UNSUPPORTED.with(|u| u.get()) && r != ERR_BAD_ARGS => ERR_UNSUPPORTED,
        Ok(r) => r,
        Err(_) => ERR_PANIC,
    }
}

/// Builds a [`Snap`] from the caller's arrays, checking every offset so a bad snapshot cannot read stray memory.
///
/// # Safety
/// `region` points to 6 ints (x0, y0, z0, nx, ny, nz); the other pointers to arrays as long as its cell count
/// (`box_start` one longer, `factors` three per cell) and `boxes` to `n_boxes * 6` doubles.
unsafe fn snap<'a>(region: *const i32, box_start: *const i32, boxes: *const f64, n_boxes: i32, flags: *const u8, factors: *const f32) -> Option<Snap<'a>> {
    if region.is_null() || box_start.is_null() || flags.is_null() || factors.is_null() || n_boxes < 0 || (n_boxes > 0 && boxes.is_null()) {
        return None;
    }
    let r = unsafe { slice::from_raw_parts(region, 6) };
    let (nx, ny, nz) = (r[3], r[4], r[5]);
    if nx <= 0 || ny <= 0 || nz <= 0 {
        return None;
    }
    let cells = nx as i64 * ny as i64 * nz as i64;
    if cells > 64_000_000 {
        return None;
    }
    let cells = cells as usize;
    let box_start = unsafe { slice::from_raw_parts(box_start, cells + 1) };
    if box_start[0] != 0 || box_start[cells] != n_boxes || box_start.windows(2).any(|p| p[1] < p[0]) {
        return None;
    }
    Some(Snap {
        x0: r[0],
        y0: r[1],
        z0: r[2],
        nx,
        ny,
        nz,
        box_start,
        boxes: if n_boxes == 0 { &[] } else { unsafe { slice::from_raw_parts(boxes, n_boxes as usize * 6) } },
        flags: unsafe { slice::from_raw_parts(flags, cells) },
        factors: unsafe { slice::from_raw_parts(factors, cells * 3) },
        oob: AtomicBool::new(false),
    })
}

/// `TasBrute.search` over a snapshot.
///
/// * `state`: 15 doubles, the start (see [`Sim::read`]).
/// * `params`: yaw0, beam, maxTicks, maxTurn, allowBack, floorY, goalX, goalY, goalZ, grab, threads.
/// * `turns`: `n_turns` floats.
/// * out: up to `out_cap` ticks of yaw, input and jump, and the end state (15 doubles).
///
/// Returns the plan's tick count, or [`ERR_NONE`] (no plan), [`ERR_OUT_OF_REGION`] (needs a bigger snapshot),
/// [`ERR_UNSUPPORTED`] (leave it to Java), [`ERR_TOO_SMALL`] (out buffers), [`ERR_BAD_ARGS`] or [`ERR_PANIC`].
///
/// # Safety
/// All pointers must be valid for the lengths described.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ost_tas_search(
    region: *const i32,
    box_start: *const i32,
    boxes: *const f64,
    n_boxes: i32,
    flags: *const u8,
    factors: *const f32,
    state: *const f64,
    params: *const f64,
    turns: *const f32,
    n_turns: i32,
    out_yaw: *mut f32,
    out_input: *mut i32,
    out_jump: *mut u8,
    out_cap: i32,
    out_state: *mut f64,
) -> i32 {
    guard(|| {
        let Some(w) = (unsafe { snap(region, box_start, boxes, n_boxes, flags, factors) }) else {
            return ERR_BAD_ARGS;
        };
        if state.is_null() || params.is_null() || turns.is_null() || !(0..=64).contains(&n_turns) || out_cap < 0
            || out_yaw.is_null() || out_input.is_null() || out_jump.is_null() || out_state.is_null()
        {
            return ERR_BAD_ARGS;
        }
        let start = Sim::read(unsafe { slice::from_raw_parts(state, STATE_LEN) });
        let pr = unsafe { slice::from_raw_parts(params, 11) };
        let max_ticks = pr[2] as i32;
        if !(0..=10_000).contains(&max_ticks) {
            return ERR_BAD_ARGS;
        }
        let p = tas::Params {
            yaw0: pr[0] as f32,
            beam: pr[1] as i32,
            max_ticks,
            max_turn: pr[3] as f32,
            allow_back: pr[4] != 0.0,
            floor_y: pr[5],
            gx: pr[6] as i32,
            gy: pr[7] as i32,
            gz: pr[8] as i32,
            grab: pr[9] != 0.0,
            threads: (pr[10] as i32).clamp(1, 64) as usize,
            turns: unsafe { slice::from_raw_parts(turns, n_turns as usize) },
        };
        match tas::search(&w, &start, &p) {
            tas::Outcome::Found(plan) => {
                let n = plan.yaw.len();
                if n > out_cap as usize {
                    return ERR_TOO_SMALL;
                }
                let (oy, oi, oj, os) = unsafe {
                    (
                        slice::from_raw_parts_mut(out_yaw, n),
                        slice::from_raw_parts_mut(out_input, n),
                        slice::from_raw_parts_mut(out_jump, n),
                        slice::from_raw_parts_mut(out_state, STATE_LEN),
                    )
                };
                oy.copy_from_slice(&plan.yaw);
                oi.copy_from_slice(&plan.input);
                for (o, &j) in oj.iter_mut().zip(&plan.jump) {
                    *o = j as u8;
                }
                plan.end.write(os);
                n as i32
            }
            tas::Outcome::None => ERR_NONE,
            tas::Outcome::OutOfRegion => ERR_OUT_OF_REGION,
            tas::Outcome::TooBig => ERR_UNSUPPORTED,
        }
    })
}

/// Rolls `PlayerSim.tick` over `n` given inputs and writes the state after every tick (`n * 15` doubles). For the
/// parity tests: the same inputs through the Java sim must give the same bits.
///
/// # Safety
/// All pointers must be valid for the lengths described.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ost_sim_run(
    region: *const i32,
    box_start: *const i32,
    boxes: *const f64,
    n_boxes: i32,
    flags: *const u8,
    factors: *const f32,
    state: *const f64,
    n: i32,
    yaw: *const f32,
    input: *const i32,
    strafe: *const i32,
    sprint: *const u8,
    jump: *const u8,
    out_states: *mut f64,
) -> i32 {
    guard(|| {
        let Some(w) = (unsafe { snap(region, box_start, boxes, n_boxes, flags, factors) }) else {
            return ERR_BAD_ARGS;
        };
        if state.is_null() || n < 0 || yaw.is_null() || input.is_null() || strafe.is_null() || sprint.is_null() || jump.is_null() || out_states.is_null() {
            return ERR_BAD_ARGS;
        }
        let n = n as usize;
        let mut s = Sim::read(unsafe { slice::from_raw_parts(state, STATE_LEN) });
        let (yaw, input, strafe, sprint, jump) = unsafe {
            (
                slice::from_raw_parts(yaw, n),
                slice::from_raw_parts(input, n),
                slice::from_raw_parts(strafe, n),
                slice::from_raw_parts(sprint, n),
                slice::from_raw_parts(jump, n),
            )
        };
        let out = unsafe { slice::from_raw_parts_mut(out_states, n * STATE_LEN) };
        let mut buf = Vec::with_capacity(64);
        for t in 0..n {
            s.tick(&w, &mut buf, yaw[t], input[t], strafe[t], sprint[t] != 0, jump[t] != 0);
            if w.missed() {
                return ERR_OUT_OF_REGION;
            }
            s.write(&mut out[t * STATE_LEN..]);
        }
        n as i32
    })
}

/// `StrictMath.sin` and `cos` of each of `n` doubles, for the parity tests.
///
/// # Safety
/// `x` holds `n` doubles, `out` room for `2 * n`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ost_sincos(x: *const f64, n: i32, out: *mut f64) -> i32 {
    guard(|| {
        if x.is_null() || out.is_null() || n < 0 {
            return ERR_BAD_ARGS;
        }
        let (x, out) = unsafe { (slice::from_raw_parts(x, n as usize), slice::from_raw_parts_mut(out, 2 * n as usize)) };
        for (i, &v) in x.iter().enumerate() {
            out[2 * i] = jmath::sin(v);
            out[2 * i + 1] = jmath::cos(v);
        }
        n
    })
}
