//! Java's arithmetic, bit for bit: `StrictMath.sin`/`cos` (fdlibm, as in the JDK's `FdLibm.java`), `Math.round`,
//! `Math.min`/`max` and the saturating `(int)` cast. The searches must give the same answer as the Java code, so
//! nothing here may be "close enough".

use std::cell::Cell;

thread_local! {
    /// Set when an argument needs fdlibm's large-argument reduction, which is not ported: the caller falls back to Java.
    pub static UNSUPPORTED: Cell<bool> = const { Cell::new(false) };
}

const EXP_SIGNIF_BITS: i32 = 0x7fff_ffff;
const EXP_BITS: i32 = 0x7ff0_0000;

#[inline]
fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as i32
}

#[inline]
fn hi_lo(high: i32, low: i32) -> f64 {
    f64::from_bits(((high as u32 as u64) << 32) | (low as u32 as u64))
}

const S1: f64 = -1.666_666_666_666_663_243_48e-01;
const S2: f64 = 8.333_333_333_322_489_461_24e-03;
const S3: f64 = -1.984_126_982_985_794_931_34e-04;
const S4: f64 = 2.755_731_370_707_006_767_89e-06;
const S5: f64 = -2.505_076_025_340_686_341_95e-08;
const S6: f64 = 1.589_690_995_211_550_102_21e-10;

const C1: f64 = 4.166_666_666_666_660_190_37e-02;
const C2: f64 = -1.388_888_888_887_410_957_49e-03;
const C3: f64 = 2.480_158_728_947_672_941_78e-05;
const C4: f64 = -2.755_731_435_139_066_330_35e-07;
const C5: f64 = 2.087_572_321_298_174_827_90e-09;
const C6: f64 = -1.135_964_755_778_819_482_65e-11;

fn kernel_sin(x: f64, y: f64, iy: i32) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix < 0x3e40_0000 && (x as i32) == 0 {
        return x;
    }
    let z = x * x;
    let v = z * x;
    let r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if iy == 0 { x + v * (S1 + z * r) } else { x - ((z * (0.5 * y - v * r) - y) - v * S1) }
}

fn kernel_cos(x: f64, y: f64) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix < 0x3e40_0000 && (x as i32) == 0 {
        return 1.0;
    }
    let z = x * x;
    let r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if ix < 0x3FD3_3333 {
        1.0 - (0.5 * z - (z * r - x * y))
    } else {
        let qx = if ix > 0x3fe9_0000 { 0.28125 } else { hi_lo(ix - 0x0020_0000, 0) };
        let hz = 0.5 * z - qx;
        let a = 1.0 - qx;
        a - (hz - (z * r - x * y))
    }
}

const NPIO2_HW: [i32; 32] = [
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A,
    0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC,
    0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
];

const INVPIO2: f64 = 6.366_197_723_675_813_824_33e-01;
const PIO2_1: f64 = 1.570_796_326_734_125_614_17e+00;
const PIO2_1T: f64 = 6.077_100_506_506_192_249_32e-11;
const PIO2_2: f64 = 6.077_100_506_303_965_976_60e-11;
const PIO2_2T: f64 = 2.022_266_248_795_950_631_54e-21;
const PIO2_3: f64 = 2.022_266_248_711_166_455_80e-21;
const PIO2_3T: f64 = 8.478_427_660_368_899_569_97e-32;

/// `__ieee754_rem_pio2` up to `2^19 * pi/2`; beyond that sets [`UNSUPPORTED`] and returns nonsense.
fn rem_pio2(x: f64) -> (i32, f64, f64) {
    let hx = hi(x);
    let ix = hx & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        return (0, x, 0.0);
    }
    if ix < 0x4002_d97c {
        if hx > 0 {
            let mut z = x - PIO2_1;
            if ix != 0x3ff9_21fb {
                let y0 = z - PIO2_1T;
                return (1, y0, (z - y0) - PIO2_1T);
            }
            z -= PIO2_2;
            let y0 = z - PIO2_2T;
            return (1, y0, (z - y0) - PIO2_2T);
        } else {
            let mut z = x + PIO2_1;
            if ix != 0x3ff9_21fb {
                let y0 = z + PIO2_1T;
                return (-1, y0, (z - y0) + PIO2_1T);
            }
            z += PIO2_2;
            let y0 = z + PIO2_2T;
            return (-1, y0, (z - y0) + PIO2_2T);
        }
    }
    if ix <= 0x4139_21fb {
        let mut t = jabs(x);
        let n = (t * INVPIO2 + 0.5) as i32;
        let fnn = n as f64;
        let mut r = t - fnn * PIO2_1;
        let mut w = fnn * PIO2_1T;
        let mut y0;
        if n < 32 && ix != NPIO2_HW[(n - 1) as usize] {
            y0 = r - w;
        } else {
            let j = ix >> 20;
            y0 = r - w;
            let mut i = j - ((hi(y0) >> 20) & 0x7ff);
            if i > 16 {
                t = r;
                w = fnn * PIO2_2;
                r = t - w;
                w = fnn * PIO2_2T - ((t - r) - w);
                y0 = r - w;
                i = j - ((hi(y0) >> 20) & 0x7ff);
                if i > 49 {
                    t = r;
                    w = fnn * PIO2_3;
                    r = t - w;
                    w = fnn * PIO2_3T - ((t - r) - w);
                    y0 = r - w;
                }
            }
        }
        let y1 = (r - y0) - w;
        return if hx < 0 { (-n, -y0, -y1) } else { (n, y0, y1) };
    }
    if ix >= EXP_BITS {
        let nan = x - x;
        return (0, nan, nan);
    }
    UNSUPPORTED.with(|u| u.set(true));
    (0, 0.0, 0.0)
}

/// `StrictMath.sin`.
pub fn sin(x: f64) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        return kernel_sin(x, 0.0, 0);
    }
    if ix >= EXP_BITS {
        return x - x;
    }
    let (n, y0, y1) = rem_pio2(x);
    match n & 3 {
        0 => kernel_sin(y0, y1, 1),
        1 => kernel_cos(y0, y1),
        2 => -kernel_sin(y0, y1, 1),
        _ => -kernel_cos(y0, y1),
    }
}

/// `StrictMath.cos`.
pub fn cos(x: f64) -> f64 {
    let ix = hi(x) & EXP_SIGNIF_BITS;
    if ix <= 0x3fe9_21fb {
        return kernel_cos(x, 0.0);
    }
    if ix >= EXP_BITS {
        return x - x;
    }
    let (n, y0, y1) = rem_pio2(x);
    match n & 3 {
        0 => kernel_cos(y0, y1),
        1 => -kernel_sin(y0, y1, 1),
        2 => -kernel_cos(y0, y1),
        _ => kernel_sin(y0, y1, 1),
    }
}

/// `Math.toRadians`.
#[inline]
pub fn to_radians(deg: f64) -> f64 {
    deg * 0.017453292519943295
}

/// `Math.abs(double)`: clears the sign bit.
#[inline]
pub fn jabs(a: f64) -> f64 {
    f64::from_bits(a.to_bits() & 0x7fff_ffff_ffff_ffff)
}

/// `Math.min(double, double)`.
#[inline]
pub fn jmin(a: f64, b: f64) -> f64 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a <= b { a } else { b }
}

/// `Math.max(double, double)`.
#[inline]
pub fn jmax(a: f64, b: f64) -> f64 {
    if a != a {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a >= b { a } else { b }
}

/// `Math.round(double)`: floor(a + 1/2) without the intermediate rounding, NaN to 0, saturating.
#[inline]
pub fn round_d(a: f64) -> i64 {
    let bits = a.to_bits() as i64;
    let biased_exp = (bits & 0x7FF0_0000_0000_0000) >> 52;
    let shift = (53 - 2 + 1023) - biased_exp;
    if (shift & -64) == 0 {
        let mut r = (bits & 0x000F_FFFF_FFFF_FFFF) | 0x0010_0000_0000_0000;
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i64
    }
}

/// `Math.round(float)`.
#[inline]
pub fn round_f(a: f32) -> i32 {
    let bits = a.to_bits() as i32;
    let biased_exp = (bits & 0x7F80_0000) >> 23;
    let shift = (24 - 2 + 127) - biased_exp;
    if (shift & -32) == 0 {
        let mut r = (bits & 0x007F_FFFF) | 0x0080_0000;
        if bits < 0 {
            r = -r;
        }
        ((r >> shift) + 1) >> 1
    } else {
        a as i32
    }
}

/// `PlayerSim.floor`: Java's saturating `(int)` cast (NaN to 0), stepped down for negatives.
#[inline]
pub fn floor_i(v: f64) -> i32 {
    let i = v as i32;
    if v < i as f64 { i - 1 } else { i }
}

/// `Double.compare`.
#[inline]
pub fn dcompare(a: f64, b: f64) -> std::cmp::Ordering {
    use std::cmp::Ordering::*;
    if a < b {
        return Less;
    }
    if a > b {
        return Greater;
    }
    let canon = |v: f64| if v != v { 0x7ff8_0000_0000_0000i64 } else { v.to_bits() as i64 };
    canon(a).cmp(&canon(b))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounding_matches_java() {
        assert_eq!(round_d(0.49999999999999994), 0);
        assert_eq!(round_d(-0.5), 0);
        assert_eq!(round_d(-1.5), -1);
        assert_eq!(round_d(2.5), 3);
        assert_eq!(round_d(f64::NAN), 0);
        assert_eq!(round_d(1e300), i64::MAX);
        assert_eq!(round_d(-1e300), i64::MIN);
        assert_eq!(round_f(-2.5), -2);
        assert_eq!(round_f(f32::NAN), 0);
        assert_eq!(round_f(0.49999997), 0);
    }

    #[test]
    fn trig_close() {
        for k in -20000..20000 {
            let x = k as f64 * 0.0137;
            assert!((sin(x) - x.sin()).abs() < 1e-15, "{x}");
            assert!((cos(x) - x.cos()).abs() < 1e-15, "{x}");
        }
    }
}
