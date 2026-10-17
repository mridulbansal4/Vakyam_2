# Vakyam: positioning for OpenCV AI Competition 2026

Three lenses, used in this order:

1. **Thiel**: where is the gap no one else will fill?
2. **Karp**: how do we say it so it sounds like conviction, not a feature list?
3. **Jane**: who is reading it, and what will make them remember it?

Ground rule: the rules disqualify entries that *"misrepresent capabilities, results, benchmarks,
or the role of human review."* Every claim below must be backed by a number we measured. We
persuade with the truth, framed well.

---

## 1. Thiel: the gap

### What the field will submit

The competition's themes are Physical AI, inspection, SLAM, digital twins and drones. Most
entries will give **a machine** eyes: a robot that inspects a pipe, a camera that counts
shoppers, a drone that maps a field. Judges will see dozens of these, and they will blur
together.

**The gap:** almost no one will submit a system where the vision output becomes **a human
being's voice**. Fewer still will have a real product running on a real phone, with device
screenshots, 310 passing tests, and two languages.

> **Every other entry gives a machine eyes. Vakyam gives a person their voice back.**

### The secret (what we know that others don't)

Everyone in eye-controlled communication competes on **tracker accuracy**: better cameras,
infrared, more pixels. That is the wrong race.

**The real bottleneck is how many decisions the patient has to make.** A person with ALS
tires after a few dozen deliberate eye movements. So the winning system is not the one that
sees most precisely. It is the one that turns **the fewest eye movements into the most
meaning.**

- Six coarse gestures, not a 30-key on-screen keyboard. Coarse means robust.
- One gesture per letter *group*, not per letter.
- A sentence from two keywords, not from twenty letters.
- The caregiver's spoken question is used as context, so the answer is shorter still.

This is why a cheap phone camera is enough. **We don't need perfect vision because we
ask vision a simpler question.** That is a computer-vision design insight, and it is ours.

### The second secret: AAC fails at setup, not in use

Communication devices are often abandoned, and a big reason is that setting them up and
recalibrating them needs a specialist who isn't in the room. (Before the report, find and
cite one published figure on AAC abandonment. Do not invent a number.)

So **the AWS part is not an add-on. It answers the real failure.** The cloud agent does the
setup a specialist therapist would do: it sees what is wrong with the calibration, fixes it,
and checks again.

### The monopoly niche (start small, own it)

Do not pitch "AAC for everyone." Pitch the segment nobody serves:

> **Hindi-English speaking families caring for someone with ALS, motor neuron disease or a
> stroke. They own an Android phone, have no budget for a dedicated eye-tracking device,
> and find no product that speaks the way they speak.**

Own that niche first. Expand to the next one later: every country where people mix two
languages when they talk.

### The north-star number

Competitors report accuracy. We report **time to first sentence**: the time from opening
the app to the patient's first sentence spoken aloud. Then **words per minute**.
Nobody else measures this, so nobody else can beat us on it.

---

## 2. Karp: the wording

Short declaratives. Moral weight. No hedging, no buzzwords, no "leveraging."
Speak about what the system *refuses* to do as much as what it does.

### The thesis (opening of the report and the video)

> The question in computer vision is no longer whether a machine can see.
> It is what the machine is seeing *for*.
>
> We built a system whose only purpose is to let a person who cannot move or speak finish
> their own sentence. Not a caregiver's guess. Not a yes or a no. Their sentence, in their
> language, in their voice.

### The beliefs (one line each, use as section openers)

- **Dignity is the ability to finish your own sentence.**
- **A person should not need the internet to say they are in pain.** The conversation never
  leaves the phone.
- **The device already in the house is the device.** Accessibility should not cost a year's
  wages.
- **The cloud should make the phone need it less.** AWS coaches the setup. It never stands
  between the patient and their words.
- **The machine proposes, the human approves.** No setting changes without a caregiver's tap.
- **We publish our failures.** A system for vulnerable people that hides its error cases
  should not be trusted.

### Names that carry weight

| Old (feature-speak) | New |
|---|---|
| Setup Coach agent | **The Calibration Coach**: a specialist's judgement, available at 2 a.m. |
| Eye Lab web demo | **Speak Without Hands**: the judge tries it in the browser |
| Fallback sentence | **The never-silent guarantee** |
| Privacy mode | **Speech stays home** |
| Typing test | **Time to first sentence** |

### Lines to cut

"Revolutionary", "leveraging AI", "seamless", "game-changing", "empowering", "we hope to".
Replace each with a number or a plain verb.

---

## 3. Jane: the reader

Patrick Jane wins because he reads the person in front of him and shows them what they
already believe. Our readers are the judges.

### Read the judges

- **Gary Bradski, founder of OpenCV.** He started OpenCV to put computer vision in
  everyone's hands, for free. Vakyam *is* that idea: vision on the cheapest camera there is,
  for the people who need it most. **Tell him this is what OpenCV was for.** Show OpenCV 5
  doing the real work: YuNet, CLAHE, `matchTemplate`, `solvePnP`, each with a measured
  before and after. He will look at the code, so the depth has to be real.
- **Phil Nelson, Director of Content & Creative.** He runs OpenCV Live and judges the story
  and the video. **Give him a video he could air.** The first 20 seconds decide it. No title
  cards, no architecture slide first.
- **Every judge is tired.** Each entry gets at least two scores, and they read many entries.
  Make scoring effortless (below).

### The moves

1. **Make the judge feel it before they understand it.** The web demo opens with one
   instruction: *"For the next 30 seconds, you cannot move or speak. Answer this question
   with your eyes."* The judge's webcam reads their eyes, and their browser speaks their
   answer. Nobody forgets the first time a machine spoke for them. This one move turns a
   judge from evaluator into user.
2. **Open on a person, not on technology.** First shot: a caregiver asks *"Khana khaoge?"*
   ("Will you eat?"). Silence. Eyes move. The phone answers *"Haan, mujhe dal chawal
   chahiye"* ("Yes, I'd like dal and rice"). Then the title. Use only consenting
   volunteers, and say so on screen. Never fake a patient.
3. **Peak and end.** People remember the strongest moment and the last one. The peak is
   the first spoken sentence. The end is a failure case, shown calmly, then the line
   *"we publish our failures."* Ending on honesty makes everything before it believable.
4. **Give them the sentence they'll repeat to the other judge.** *"Every other entry gives a
   machine eyes. This one gives a person their voice back."* Say it once in the video, once
   in the report. No more.
5. **Hand them the scorecard.** The report's first page is a table that mirrors the rubric
   exactly, so each judge can tick their boxes without searching:

   | Rubric item | Weight | Where to find our evidence |
   |---|---|---|
   | Technical execution (OpenCV 5) | 30% | §4: pipeline, old vs new benchmark |
   | Innovation | 20% | §2: fewer decisions, not better tracking |
   | Real-world impact | 20% | §1: the niche, time to first sentence |
   | User experience / accessibility | 10% | §5: six gestures, two languages, never silent |
   | Documentation and presentation | 10% | this report, video, README |
   | Cloud, reproducibility, responsible use | 10% | §6: one-command deploy, speech stays home |

6. **Pre-empt the doubt.** A sharp judge will think *"phone cameras aren't accurate
   enough."* Answer it before they ask: *"They aren't, for 30 keys. They are, for six
   directions. We designed the problem so the camera could win."*

### Hard limits

- No staged patient footage. No invented numbers. No "clinically proven."
- Emotion comes from real moments, not pity. The patient is shown in control, never as
  helpless.
- Every demo claim must work live, because a judge may click it.

---

## 4. The restructured idea, in one page

**Vakyam: the voice already in your pocket.**

| Layer | What it does | Why it wins |
|---|---|---|
| **Phone (the voice)** | Six eye gestures → ambiguous keyboard → Gemma sentence → speech. Offline, English and Hinglish. | Real product, real device, private by design |
| **OpenCV 5 (the eyes)** | YuNet face → CLAHE eye crops → `matchTemplate` → quality gate (blur, exposure, `solvePnP` head pose) | Depth of OpenCV 5, every stage measured against the old pipeline |
| **AWS (the Calibration Coach)** | An agent reads the OpenCV diagnostics → re-records a direction / ring light / tells the caregiver how to move the phone → checks again. The caregiver approves. | Agentic Vision prize. Fixes the real reason AAC fails. |
| **AWS (Speak Without Hands)** | Browser demo: the judge's webcam → the same OpenCV 5 pipeline in Python on AWS → their sentence spoken | The required web endpoint, turned into the emotional peak |

**Story order for the report and video:**
person → problem → secret → system → proof → failures → beliefs.

**Numbers to have before submitting:** time to first sentence (with and without the Coach),
accuracy for each direction (old vs OpenCV 5), words per minute per mode, the Coach's
fix rate, and latency on AWS.
