// bench/workloads/static-shape.rs - static shape of a lean4export NDJSON file (format 3.x, canonical
// exporter lines) for *agentic-note*: study-workload-characterization.
//
//   rustc -O -o work/workloads/bin/static-shape bench/workloads/static-shape.rs
//   work/workloads/bin/static-shape EXPORT.ndjson OUTDIR
//
// Reads data only. Writes into OUTDIR:
//   heat.txt    HEAT lines in the format of spikes/machine (Exp1.heatReport), same definition of a spine:
//               an APP node referenced from at least one non-function position, head = constant
//   consts.tsv  one row per constant:
//               idx name kind hint nparams lam_arity flags static_heat seg bare_refs
//               kind: axiom def thm opaque quot ind ctor rec; flags: P = body is `fun .. self => self.i`
//               (structure projection function), S = structure-like inductive, K = recursor with K,
//               R = recursive inductive
//   segs.tsv    one row per declaration record (segment = expression records since the previous declaration):
//               seg kind first_name nodes private_nodes bytes type_nodes_est
//   summary.txt totals: private share, literal histograms, references to literals
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};

const BVAR: u8 = 0;
const SORT: u8 = 1;
const CONST: u8 = 2;
const APP: u8 = 3;
const LAM: u8 = 4;
const PI: u8 = 5;
const LET: u8 = 6;
const NATLIT: u8 = 7;
const STRLIT: u8 = 8;
const PROJ: u8 = 9;

#[inline]
fn num(b: &[u8], p: &mut usize) -> u64 {
    let mut q = *p;
    while q < b.len() && !(b[q] >= b'0' && b[q] <= b'9') {
        q += 1;
    }
    let mut v: u64 = 0;
    while q < b.len() && b[q] >= b'0' && b[q] <= b'9' {
        v = v.wrapping_mul(10).wrapping_add((b[q] - b'0') as u64);
        q += 1;
    }
    *p = q;
    v
}

fn find(b: &[u8], key: &[u8]) -> Option<usize> {
    b.windows(key.len()).position(|w| w == key).map(|i| i + key.len())
}

// ---- minimal JSON for declaration lines
#[derive(Debug)]
enum J {
    N(u64),
    S(String),
    B(bool),
    A(Vec<J>),
    O(Vec<(String, J)>),
}
impl J {
    fn get(&self, k: &str) -> Option<&J> {
        if let J::O(v) = self {
            for (kk, vv) in v {
                if kk == k {
                    return Some(vv);
                }
            }
        }
        None
    }
    fn n(&self, k: &str) -> u64 {
        match self.get(k) {
            Some(J::N(x)) => *x,
            _ => u64::MAX,
        }
    }
    fn b(&self, k: &str) -> bool {
        matches!(self.get(k), Some(J::B(true)))
    }
    fn arr(&self, k: &str) -> &[J] {
        match self.get(k) {
            Some(J::A(v)) => v,
            _ => &[],
        }
    }
}
fn json(b: &[u8], p: &mut usize) -> J {
    while b[*p] == b' ' {
        *p += 1;
    }
    match b[*p] {
        b'{' => {
            *p += 1;
            let mut v = Vec::new();
            if b[*p] == b'}' {
                *p += 1;
                return J::O(v);
            }
            loop {
                let k = match json(b, p) {
                    J::S(s) => s,
                    _ => String::new(),
                };
                *p += 1; // ':'
                let val = json(b, p);
                v.push((k, val));
                let c = b[*p];
                *p += 1;
                if c == b'}' {
                    return J::O(v);
                }
            }
        }
        b'[' => {
            *p += 1;
            let mut v = Vec::new();
            if b[*p] == b']' {
                *p += 1;
                return J::A(v);
            }
            loop {
                v.push(json(b, p));
                let c = b[*p];
                *p += 1;
                if c == b']' {
                    return J::A(v);
                }
            }
        }
        b'"' => {
            *p += 1;
            let s = *p;
            while b[*p] != b'"' {
                *p += if b[*p] == b'\\' { 2 } else { 1 };
            }
            let r = String::from_utf8_lossy(&b[s..*p]).into_owned();
            *p += 1;
            J::S(r)
        }
        b't' => {
            *p += 4;
            J::B(true)
        }
        b'f' => {
            *p += 5;
            J::B(false)
        }
        b'n' => {
            *p += 4;
            J::N(u64::MAX)
        }
        _ => J::N(num(b, p)),
    }
}

struct Konst {
    name: u32,
    kind: &'static str,
    hint: String,
    ty: u32,
    val: u32, // u32::MAX = none
    flags: String,
    seg: u32,
}

struct Seg {
    kind: &'static str,
    first_name: u32,
    start: u32,
    end: u32,
    bytes: u64,
    ty: u32,
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("usage: static-shape EXPORT.ndjson OUTDIR");
        std::process::exit(2);
    }
    let f = File::open(&args[1]).expect("open export");
    let mut rd = BufReader::with_capacity(1 << 24, f);
    let outdir = &args[2];
    std::fs::create_dir_all(outdir).unwrap();

    let mut kind: Vec<u8> = Vec::new();
    let mut ea: Vec<u32> = Vec::new();
    let mut eb: Vec<u32> = Vec::new();
    let mut ec: HashMap<u32, u32> = HashMap::new(); // LET body, PROJ struct
    let mut nonfn: Vec<bool> = Vec::new();
    let mut ext: Vec<bool> = Vec::new();
    let mut name_pre: Vec<u32> = vec![0];
    let mut name_comp: Vec<String> = vec![String::new()];
    let mut consts: Vec<Konst> = Vec::new();
    let mut segs: Vec<Seg> = Vec::new();
    let mut extra_roots: Vec<u32> = Vec::new();
    let mut seg_start: u32 = 0;
    let mut seg_bytes: u64 = 0;
    // literal statistics
    let mut nat_hist = [0u64; 6]; // 0/1 | <2^8 | <2^31 | <2^63 | <2^64.. (<=20 digits) | bigger
    let mut nat_max_digits = 0usize;
    let mut str_hist = [0u64; 6]; // 0 | 1..8 | 9..64 | 65..1024 | 1025..65536 | bigger (bytes, escaped form)
    let mut str_bytes: u64 = 0;
    let mut nat_refs: u64 = 0;
    let mut str_refs: u64 = 0;
    let mut bare_const_expr: u64 = 0;

    let mut line: Vec<u8> = Vec::with_capacity(1 << 16);
    let mut lineno: u64 = 0;
    macro_rules! child {
        ($c:expr, $nonfn:expr) => {{
            let c = $c as usize;
            if $nonfn {
                nonfn[c] = true;
            }
            if ($c) < seg_start {
                ext[c] = true;
            }
            match kind[c] {
                NATLIT => nat_refs += 1,
                STRLIT => str_refs += 1,
                _ => {}
            }
        }};
    }
    macro_rules! push {
        ($k:expr, $a:expr, $b:expr) => {{
            kind.push($k);
            ea.push($a);
            eb.push($b);
            nonfn.push(false);
            ext.push(false);
        }};
    }
    loop {
        line.clear();
        let n = rd.read_until(b'\n', &mut line).expect("read");
        if n == 0 {
            break;
        }
        lineno += 1;
        seg_bytes += n as u64;
        let b = &line[..];
        if b.len() < 6 {
            continue;
        }
        let ne = kind.len() as u32;
        let (c2, c3) = (b[2], b[3]);
        if c2 == b'a' && c3 == b'p' {
            // {"app":{"arg":A,"fn":F},"ie":N}
            let mut p = 0;
            let arg = num(b, &mut p) as u32;
            let fun = num(b, &mut p) as u32;
            let id = num(b, &mut p) as u32;
            assert!(id == ne, "non-canonical app line {lineno}");
            child!(arg, true);
            child!(fun, false);
            push!(APP, fun, arg);
        } else if c2 == b'i' && c3 == b'e' {
            let mut p = 0;
            let id = num(b, &mut p) as u32;
            assert!(id == ne, "non-canonical ie line {lineno}");
            let k0 = b[p + 2];
            let k1 = b[p + 3];
            if k0 == b'l' && k1 == b'a' {
                let body = num(b, &mut p) as u32;
                let _name = num(b, &mut p);
                let ty = num(b, &mut p) as u32;
                child!(ty, true);
                child!(body, true);
                push!(LAM, ty, body);
            } else if k0 == b'l' && k1 == b'e' {
                let body = num(b, &mut p) as u32;
                let _name = num(b, &mut p);
                let ty = num(b, &mut p) as u32;
                let val = num(b, &mut p) as u32;
                child!(ty, true);
                child!(val, true);
                child!(body, true);
                ec.insert(ne, body);
                push!(LET, ty, val);
            } else if k0 == b'p' {
                let idx = num(b, &mut p) as u32;
                let st = num(b, &mut p) as u32;
                let tn = num(b, &mut p) as u32;
                child!(st, true);
                ec.insert(ne, st);
                push!(PROJ, tn, idx);
            } else if k0 == b's' && k1 == b'o' {
                let l = num(b, &mut p) as u32;
                push!(SORT, l, 0);
            } else if k0 == b'n' && k1 == b'a' {
                // "natVal":"digits"
                let s = find(b, b"\"natVal\":\"").unwrap();
                let mut e = s;
                while b[e] != b'"' {
                    e += 1;
                }
                let digits = &b[s..e];
                nat_max_digits = nat_max_digits.max(digits.len());
                let bucket = if digits.len() > 20 {
                    5
                } else {
                    let v: u128 = std::str::from_utf8(digits).unwrap().parse().unwrap();
                    if v <= 1 {
                        0
                    } else if v < 256 {
                        1
                    } else if v < (1u128 << 31) {
                        2
                    } else if v < (1u128 << 63) {
                        3
                    } else if v < (1u128 << 64) {
                        4
                    } else {
                        5
                    }
                };
                nat_hist[bucket] += 1;
                push!(NATLIT, 0, 0);
            } else if k0 == b's' && k1 == b't' {
                let s = find(b, b"\"strVal\":\"").unwrap();
                let mut e = s;
                while b[e] != b'"' {
                    e += if b[e] == b'\\' { 2 } else { 1 };
                }
                let len = e - s;
                str_bytes += len as u64;
                let bucket = match len {
                    0 => 0,
                    1..=8 => 1,
                    9..=64 => 2,
                    65..=1024 => 3,
                    1025..=65536 => 4,
                    _ => 5,
                };
                str_hist[bucket] += 1;
                push!(STRLIT, len as u32, 0);
            } else if k0 == b'm' {
                // mdata: {"ie":N,"mdata":{"data":..,"expr":E}} - transparent
                let e = find(b, b"\"expr\":").map(|q| {
                    let mut q2 = q;
                    num(b, &mut q2) as u32
                });
                let e = e.expect("mdata expr");
                child!(e, true);
                push!(kind[e as usize], ea[e as usize], eb[e as usize]);
            } else {
                panic!("unsupported expr line {lineno}");
            }
        } else if c2 == b'f' {
            let mut p = 0;
            let body = num(b, &mut p) as u32;
            let _name = num(b, &mut p);
            let ty = num(b, &mut p) as u32;
            let id = num(b, &mut p) as u32;
            assert!(id == ne, "non-canonical forallE line {lineno}");
            child!(ty, true);
            child!(body, true);
            push!(PI, ty, body);
        } else if c2 == b'c' {
            let mut p = 0;
            let name = num(b, &mut p) as u32;
            while b[p] != b']' {
                p += 1;
            }
            let id = num(b, &mut p) as u32;
            assert!(id == ne, "non-canonical const line {lineno}");
            bare_const_expr += 1;
            push!(CONST, name, 0);
        } else if c2 == b'b' {
            let mut p = 0;
            let idx = num(b, &mut p) as u32;
            let id = num(b, &mut p) as u32;
            assert!(id == ne, "non-canonical bvar line {lineno}");
            push!(BVAR, idx, 0);
        } else if c2 == b'i' && c3 == b'n' && b[4] == b'"' {
            let mut p = 0;
            let id = num(b, &mut p) as usize;
            if id >= name_pre.len() {
                name_pre.resize(id + 1, 0);
                name_comp.resize(id + 1, String::new());
            }
            if b[p + 2] == b'n' {
                let i = num(b, &mut p);
                name_pre[id] = num(b, &mut p) as u32;
                name_comp[id] = i.to_string();
            } else {
                name_pre[id] = num(b, &mut p) as u32;
                p += 8;
                let s = p;
                while b[p] != b'"' {
                    p += if b[p] == b'\\' { 2 } else { 1 };
                }
                name_comp[id] = String::from_utf8_lossy(&b[s..p]).into_owned();
            }
        } else if c2 == b'i' && c3 == b'l' {
            // level
        } else if c2 == b'm' {
            // meta
            seg_bytes = 0;
        } else {
            let mut p = 0;
            let top = json(b, &mut p);
            let J::O(ref kv) = top else { panic!("bad decl line {lineno}") };
            let (key, d) = (&kv[0].0, &kv[0].1);
            let seg = segs.len() as u32;
            let mut first_name = 0u32;
            let mut seg_ty = u32::MAX;
            let mut add = |consts: &mut Vec<Konst>, name: u64, kindc: &'static str, hint: String, ty: u64, val: u64, flags: String| {
                consts.push(Konst { name: name as u32, kind: kindc, hint, ty: ty as u32, val: if val == u64::MAX { u32::MAX } else { val as u32 }, flags, seg });
            };
            let segkind: &'static str = match key.as_str() {
                "axiom" => {
                    add(&mut consts, d.n("name"), "axiom", String::new(), d.n("type"), u64::MAX, String::new());
                    first_name = d.n("name") as u32;
                    seg_ty = d.n("type") as u32;
                    "axiom"
                }
                "def" => {
                    let hint = match d.get("hints") {
                        Some(J::S(s)) => s.clone(),
                        Some(J::O(v)) => match &v[0].1 {
                            J::N(h) => format!("regular{h}"),
                            _ => "regular".into(),
                        },
                        _ => String::new(),
                    };
                    add(&mut consts, d.n("name"), "def", hint, d.n("type"), d.n("value"), String::new());
                    first_name = d.n("name") as u32;
                    seg_ty = d.n("type") as u32;
                    "def"
                }
                "thm" => {
                    add(&mut consts, d.n("name"), "thm", String::new(), d.n("type"), d.n("value"), String::new());
                    first_name = d.n("name") as u32;
                    seg_ty = d.n("type") as u32;
                    "thm"
                }
                "opaque" => {
                    add(&mut consts, d.n("name"), "opaque", String::new(), d.n("type"), d.n("value"), String::new());
                    first_name = d.n("name") as u32;
                    seg_ty = d.n("type") as u32;
                    "opaque"
                }
                "quot" => {
                    add(&mut consts, d.n("name"), "quot", String::new(), d.n("type"), u64::MAX, String::new());
                    first_name = d.n("name") as u32;
                    "quot"
                }
                "inductive" => {
                    let types = d.arr("types");
                    let ctors = d.arr("ctors");
                    for t in types {
                        let mut fl = String::new();
                        if t.b("isRec") {
                            fl.push('R');
                        }
                        // structure-like: one constructor, no indices, not recursive
                        if t.arr("ctors").len() == 1 && t.n("numIndices") == 0 && !t.b("isRec") {
                            fl.push('S');
                        }
                        if first_name == 0 {
                            first_name = t.n("name") as u32;
                        }
                        add(&mut consts, t.n("name"), "ind", format!("ctors{}", t.arr("ctors").len()), t.n("type"), u64::MAX, fl);
                    }
                    for c in ctors {
                        add(&mut consts, c.n("name"), "ctor", format!("fields{}", c.n("numFields")), c.n("type"), u64::MAX, String::new());
                    }
                    for r in d.arr("recs") {
                        let mut fl = String::new();
                        if r.b("k") {
                            fl.push('K');
                        }
                        add(&mut consts, r.n("name"), "rec", format!("rules{}", r.arr("rules").len()), r.n("type"), u64::MAX, fl);
                        for rule in r.arr("rules") {
                            extra_roots.push(rule.n("rhs") as u32);
                        }
                    }
                    "inductive"
                }
                other => panic!("unknown declaration kind {other} at line {lineno}"),
            };
            segs.push(Seg { kind: segkind, first_name, start: seg_start, end: ne, bytes: seg_bytes, ty: seg_ty });
            seg_start = ne;
            seg_bytes = 0;
        }
    }
    let n = kind.len();
    eprintln!("read {} lines, {} exprs, {} consts, {} segments", lineno, n, consts.len(), segs.len());

    // roots are non-function positions; a root older than its segment start is an external reference
    for c in &consts {
        nonfn[c.ty as usize] = true;
        if c.val != u32::MAX {
            nonfn[c.val as usize] = true;
        }
        let s = segs[c.seg as usize].start;
        if c.ty < s {
            ext[c.ty as usize] = true;
        }
        if c.val != u32::MAX && c.val < s {
            ext[c.val as usize] = true;
        }
    }
    for &r in &extra_roots {
        nonfn[r as usize] = true;
    }

    // names
    let full_name = |id: u32| -> String {
        let mut parts: Vec<&str> = Vec::new();
        let mut i = id as usize;
        while i != 0 && i < name_pre.len() {
            parts.push(&name_comp[i]);
            i = name_pre[i] as usize;
        }
        parts.reverse();
        parts.join(".")
    };
    let mut const_of_name: Vec<u32> = vec![0; name_pre.len() + 1];
    for (i, c) in consts.iter().enumerate() {
        const_of_name[c.name as usize] = i as u32 + 1;
    }
    let nc = consts.len();
    let mut nparams = vec![0u32; nc];
    let mut lam_arity = vec![0u32; nc];
    let mut max_params = 0u32;
    for (i, c) in consts.iter_mut().enumerate() {
        let mut t = c.ty as usize;
        let mut np = 0;
        while kind[t] == PI {
            np += 1;
            t = eb[t] as usize;
        }
        nparams[i] = np;
        max_params = max_params.max(np);
        if c.val != u32::MAX {
            let mut v = c.val as usize;
            let mut la = 0;
            while kind[v] == LAM {
                la += 1;
                v = eb[v] as usize;
            }
            lam_arity[i] = la;
            if la > 0 && kind[v] == PROJ {
                let s = ec[&(v as u32)] as usize;
                if kind[s] == BVAR && ea[s] == 0 {
                    c.flags.push('P');
                }
            }
        }
    }

    // static heat
    let mut heat = vec![0u64; nc];
    let mut bare = vec![0u64; nc];
    let (mut app_nodes, mut spines, mut spines_other, mut total_args, mut over, mut under) = (0u64, 0u64, 0u64, 0u64, 0u64, 0u64);
    // heat by position: spines inside theorem segments, definition segments, other
    let mut seg_of_expr_kind: Vec<u8> = vec![0; n];
    for s in &segs {
        let k = match s.kind {
            "thm" => 1u8,
            "def" => 2,
            "inductive" => 3,
            _ => 4,
        };
        for e in s.start..s.end {
            seg_of_expr_kind[e as usize] = k;
        }
    }
    let mut heat_thm = vec![0u64; nc];
    for e in 0..n {
        if kind[e] == CONST {
            let c = const_of_name[ea[e] as usize];
            if c > 0 && nonfn[e] {
                bare[c as usize - 1] += 1;
            }
            continue;
        }
        if kind[e] != APP {
            continue;
        }
        app_nodes += 1;
        if !nonfn[e] {
            continue;
        }
        let mut k = 0u32;
        let mut h = e;
        while kind[h] == APP {
            h = ea[h] as usize;
            k += 1;
        }
        let c = if kind[h] == CONST { const_of_name[ea[h] as usize] } else { 0 };
        if c == 0 {
            spines_other += 1;
            continue;
        }
        let c = c as usize - 1;
        heat[c] += 1;
        if seg_of_expr_kind[e] == 1 {
            heat_thm[c] += 1;
        }
        spines += 1;
        total_args += k as u64;
        if k > nparams[c] {
            over += 1;
        } else if k < nparams[c] {
            under += 1;
        }
    }

    // ---- heat.txt (spike format)
    let mut idx: Vec<usize> = (0..nc).collect();
    idx.sort_by(|&x, &y| heat[y].cmp(&heat[x]).then(x.cmp(&y)));
    let mut out = BufWriter::new(File::create(format!("{outdir}/heat.txt")).unwrap());
    let total = spines.max(1);
    let used = heat.iter().filter(|&&h| h > 0).count();
    writeln!(out, "HEAT exprs={} app_nodes={} constants={} spines_const_head={} spines_other_head={} args={} avg_args={:.2} over_applied={} under_applied={} max_params={}",
        n, app_nodes, nc, spines, spines_other, total_args, total_args as f64 / total as f64, over, under, max_params).unwrap();
    writeln!(out, "HEAT constants used as a spine head: {} of {} ({:.1}%)", used, nc, 100.0 * used as f64 / nc as f64).unwrap();
    let tops = [1usize, 10, 30, 100, 300, 1000, 3000, 10000, 30000];
    let shares = [0.5, 0.8, 0.9, 0.95, 0.99, 0.999];
    let (mut ti, mut si, mut acc) = (0, 0, 0u64);
    let mut a = String::from("HEAT share of all instantiations received by the hottest N constants:");
    let mut bb = String::from("HEAT constants needed to cover a share:");
    for r in 0..nc {
        acc += heat[idx[r]];
        if ti < tops.len() && r + 1 == tops[ti] {
            a.push_str(&format!(" top{}={:.1}%", tops[ti], 100.0 * acc as f64 / total as f64));
            ti += 1;
        }
        while si < shares.len() && acc as f64 >= shares[si] * total as f64 {
            bb.push_str(&format!(" {:.1}%:{}", 100.0 * shares[si], r + 1));
            si += 1;
        }
    }
    writeln!(out, "{a}").unwrap();
    writeln!(out, "{bb}").unwrap();
    for th in [1u64, 10, 100, 1000, 10_000, 100_000, 1_000_000] {
        let (mut cnt, mut sum, mut post) = (0u64, 0u64, 0u64);
        for i in 0..nc {
            if heat[i] >= th {
                cnt += 1;
                sum += heat[i];
                post += heat[i] - th;
            }
        }
        writeln!(out, "HEAT heat>={}: {} constants, {:.2}% of instantiations, {:.2}% of instantiations happen after the constant crossed the threshold",
            th, cnt, 100.0 * sum as f64 / total as f64, 100.0 * post as f64 / total as f64).unwrap();
    }
    for r in 0..40.min(nc) {
        let c = idx[r];
        writeln!(out, "HEAT #{} {} heat={} ({:.2}%) params={} kind={}{}", r + 1, full_name(consts[c].name), heat[c], 100.0 * heat[c] as f64 / total as f64, nparams[c], consts[c].kind,
            if consts[c].flags.is_empty() { String::new() } else { format!(" flags={}", consts[c].flags) }).unwrap();
    }
    drop(out);

    // ---- consts.tsv
    let mut out = BufWriter::new(File::create(format!("{outdir}/consts.tsv")).unwrap());
    writeln!(out, "idx\tname\tkind\thint\tnparams\tlam_arity\tflags\tstatic_heat\tstatic_heat_in_thm\tseg\tbare_refs").unwrap();
    for (i, c) in consts.iter().enumerate() {
        writeln!(out, "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}", i, full_name(c.name), c.kind, c.hint, nparams[i], lam_arity[i], c.flags, heat[i], heat_thm[i], c.seg, bare[i]).unwrap();
    }
    drop(out);

    // ---- segs.tsv and private share
    let mut out = BufWriter::new(File::create(format!("{outdir}/segs.tsv")).unwrap());
    writeln!(out, "seg\tkind\tfirst_name\tnodes\tprivate_nodes\tbytes\tapp_nodes\tlam_nodes\tspine_roots").unwrap();
    let mut tot_by_kind: HashMap<&str, (u64, u64, u64)> = HashMap::new();
    for (si, s) in segs.iter().enumerate() {
        let (mut private, mut apps, mut lams, mut roots) = (0u64, 0u64, 0u64, 0u64);
        for e in s.start..s.end {
            let e = e as usize;
            if !ext[e] {
                private += 1;
            }
            match kind[e] {
                APP => {
                    apps += 1;
                    if nonfn[e] {
                        roots += 1;
                    }
                }
                LAM => lams += 1,
                _ => {}
            }
        }
        let nodes = (s.end - s.start) as u64;
        let t = tot_by_kind.entry(s.kind).or_insert((0, 0, 0));
        t.0 += 1;
        t.1 += nodes;
        t.2 += private;
        let _ = s.ty;
        writeln!(out, "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}", si, s.kind, full_name(s.first_name), nodes, private, s.bytes, apps, lams, roots).unwrap();
    }
    drop(out);

    // ---- summary.txt
    let mut out = BufWriter::new(File::create(format!("{outdir}/summary.txt")).unwrap());
    writeln!(out, "exprs {n}").unwrap();
    writeln!(out, "consts {nc}").unwrap();
    writeln!(out, "segments {}", segs.len()).unwrap();
    let (mut tn, mut tp) = (0u64, 0u64);
    let mut kinds: Vec<_> = tot_by_kind.iter().collect();
    kinds.sort();
    for (k, (cnt, nodes, private)) in kinds {
        writeln!(out, "seg_kind {k} decls {cnt} nodes {nodes} private {private} private_share {:.4}", *private as f64 / (*nodes).max(1) as f64).unwrap();
        tn += nodes;
        tp += private;
    }
    writeln!(out, "all_segments nodes {tn} private {tp} private_share {:.4}", tp as f64 / tn.max(1) as f64).unwrap();
    writeln!(out, "nat_literals hist[<=1,<2^8,<2^31,<2^63,<2^64,>=2^64] {:?} max_digits {}", nat_hist, nat_max_digits).unwrap();
    writeln!(out, "nat_literal_refs {nat_refs}").unwrap();
    writeln!(out, "str_literals hist_bytes[0,1-8,9-64,65-1024,1025-65536,>65536] {:?} total_bytes {}", str_hist, str_bytes).unwrap();
    writeln!(out, "str_literal_refs {str_refs}").unwrap();
    writeln!(out, "const_exprs {bare_const_expr}").unwrap();
    writeln!(out, "proj_exprs {}", kind.iter().filter(|&&k| k == PROJ).count()).unwrap();
    writeln!(out, "let_exprs {}", kind.iter().filter(|&&k| k == LET).count()).unwrap();
    writeln!(out, "lam_exprs {}", kind.iter().filter(|&&k| k == LAM).count()).unwrap();
    writeln!(out, "pi_exprs {}", kind.iter().filter(|&&k| k == PI).count()).unwrap();
    writeln!(out, "app_exprs {app_nodes}").unwrap();
    writeln!(out, "spine_roots_const_head {spines}").unwrap();
    writeln!(out, "spine_roots_other_head {spines_other}").unwrap();
    drop(out);
}
