"""
Generates NSXL's interface sounds from scratch (pure synthesis, no samples).

    python3 tools/make_sounds.py

Writes 16-bit mono WAVs to app/src/main/assets/www/sfx/. Tweak the recipes below and re-run.
"""
import os
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'www', 'sfx')
rng = np.random.default_rng(7)


def t(dur):
    return np.arange(int(SR * dur)) / SR


def env(dur, attack=0.002, decay=0.05, shape=1.0):
    """Fast attack, exponential decay."""
    x = t(dur)
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return a * np.exp(-x / decay) ** shape


def sweep(f0, f1, dur, curve=3.0):
    """Sine whose pitch glides from f0 to f1 (exponential-ish)."""
    x = t(dur)
    k = (1 - np.exp(-curve * x / dur)) / (1 - np.exp(-curve))
    f = f0 + (f1 - f0) * k
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


def tone(f, dur, partials=((1, 1.0),)):
    x = t(dur)
    return sum(a * np.sin(2 * np.pi * f * m * x) for m, a in partials)


def noise(dur, lo=None, hi=None):
    n = rng.standard_normal(int(SR * dur))
    if lo or hi:  # crude band-pass in the frequency domain
        spec = np.fft.rfft(n)
        fr = np.fft.rfftfreq(len(n), 1 / SR)
        mask = np.ones_like(fr)
        if lo: mask *= 1 / (1 + (lo / np.maximum(fr, 1)) ** 4)
        if hi: mask *= 1 / (1 + (fr / hi) ** 4)
        n = np.fft.irfft(spec * mask, len(n))
    return n / (np.abs(n).max() or 1)


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def at(sig, delay):
    return np.concatenate([np.zeros(int(SR * delay)), sig])


def room(sig, amount=0.18, time=0.045, fb=0.45, taps=5):
    """Tiny feedback echo, just enough air for chimes."""
    out = np.concatenate([sig, np.zeros(int(SR * time * taps))])
    d = int(SR * time)
    for i in range(1, taps + 1):
        out[d * i: d * i + len(sig)] += sig * amount * (fb ** (i - 1))
    return out


def save(name, sig, peak=0.85):
    sig = sig / (np.abs(sig).max() or 1) * peak
    fade = min(len(sig), int(SR * 0.004))
    sig[-fade:] *= np.linspace(1, 0, fade)
    data = (sig * 32767).astype('<i2').tobytes()
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data)
    print(f'{name:10s} {len(sig) / SR:.3f}s')


# --- moving around: a crisp, woody tick ---------------------------------------
d = 0.045
save('tick', mix(tone(3150, d, ((1, 1), (1.51, .35), (2.03, .15))) * env(d, .0006, .006),
                 noise(.012, 2500, 9000) * env(.012, .0003, .0025) * .35), .55)

# --- select / confirm: a bright two-step blip ---------------------------------
d = 0.18
save('select', mix(sweep(1750, 2150, .05) * env(.05, .001, .02),
                   at(tone(2150, .13, ((1, 1), (2, .18))) * env(.13, .002, .04), .045)), .6)

# --- back / close: a soft downward blip ---------------------------------------
d = 0.09
save('back', sweep(980, 620, d, 4) * env(d, .002, .028), .55)

# --- edge of a list: a small muted thud ---------------------------------------
d = 0.07
save('bump', mix(sweep(1500, 420, d, 9) * env(d, .0008, .016),
                 noise(.03, 80, 900) * env(.03, .0005, .008) * .5), .7)

# --- launching a game: a quick bright cascade with a little sparkle -----------
d = 0.42
save('launch', room(mix(sweep(3100, 2100, .17, 5) * env(.17, .001, .06),
                        at(tone(2794, .2, ((1, 1), (2, .2))) * env(.2, .002, .07) * .6, .07),
                        at(tone(4186, .18) * env(.18, .002, .05) * .35, .13)), .22, .05, .4), .65)

# --- a panel or menu opening: rising whoosh into a soft ping ------------------
d = 0.3
save('open', mix(noise(d, 900, 5000) * np.linspace(0, 1, int(SR * d)) ** 2 * env(d, .2, .5) * .25,
                 sweep(1200, 2800, .2, 2) * env(.2, .12, .2) * .5,
                 at(tone(2794, .12, ((1, 1), (2, .15))) * env(.12, .001, .035), .19)), .55)

# --- a panel or menu closing: falling sweep -----------------------------------
d = 0.16
save('close', mix(sweep(2400, 850, d, 3) * env(d, .002, .05),
                  noise(.1, 700, 3000) * env(.1, .001, .03) * .15), .5)

# --- switching tabs ------------------------------------------------------------
d = 0.09
save('tab', sweep(1300, 650, d, 6) * env(d, .001, .025), .55)

# --- toggles -------------------------------------------------------------------
save('on', mix(tone(1760, .035, ((1, 1), (2, .3))) * env(.035, .0005, .008),
               at(tone(2637, .04) * env(.04, .0005, .01) * .7, .028)), .55)
save('off', sweep(790, 330, .12, 5) * env(.12, .001, .035), .55)

# --- slider step ---------------------------------------------------------------
save('step', tone(2600, .025, ((1, 1), (1.5, .2))) * env(.025, .0005, .005), .4)

# --- typing --------------------------------------------------------------------
save('type', mix(tone(1250, .02) * env(.02, .0004, .004), noise(.01, 1500, 6000) * env(.01, .0003, .002) * .3), .4)

# --- notification / toast: rising two-note chime --------------------------------
save('toast', room(mix(tone(2093, .14, ((1, 1), (2, .12))) * env(.14, .002, .05),
                       at(tone(3136, .2, ((1, 1), (2, .1))) * env(.2, .002, .07), .075)), .2, .05, .4), .55)

# --- start-up chime: soft bell arpeggio over a low pad --------------------------
bell = lambda f, dur: tone(f, dur, ((1, 1), (2.01, .35), (3.02, .12), (4.2, .05))) * env(dur, .004, dur / 3.2)
seq = [(523.25, 0.00), (659.25, 0.09), (783.99, 0.18), (1046.5, 0.27), (1567.98, 0.40)]
chime = mix(*[at(bell(f, 1.1 - i * .1) * (1 - i * .08), dly) for i, (f, dly) in enumerate(seq)],
            tone(261.63, 1.6, ((1, 1), (1.5, .3))) * env(1.6, .25, .9) * .35)
save('boot', room(chime, .25, .06, .45, 6), .7)

# --- wake: two light bell notes -------------------------------------------------
save('wake', room(mix(bell(1046.5, .4), at(bell(1567.98, .55), .08)), .22, .05, .4), .6)

# --- sleep: three falling bell notes --------------------------------------------
save('sleep', room(mix(*[at(bell(f, .5) * (1 - i * .15), i * .1) for i, f in enumerate([1046.5, 783.99, 523.25])]), .25, .06, .4), .6)

# --- quick menu appears ---------------------------------------------------------
save('menu', room(mix(sweep(660, 990, .12, 2) * env(.12, .01, .08),
                      at(tone(1318.5, .18, ((1, 1), (2, .15))) * env(.18, .002, .06), .07)), .2, .05, .4), .55)
