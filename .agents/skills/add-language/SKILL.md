---
name: add-language
description: >-
  Triggered when the user uses the `/add language <language_name>` command. This skill initiates an end-to-end multi-agent workflow to add comprehensive support for any new language in the world to the Vakyam AAC app, including internet scraping, code integration, and massive validation simulation loops.
---

# Add Language Skill

You have been invoked to add a new language to the Vakyam AAC App. This is a complex, multi-agent process that mimics the rigorous Hindi implementation workflow. 

## General Instructions
- **Do not stop until fully complete.** This acts like a `/goal` loop.
- **Use Subagents heavily.** Utilize `DeepInvestigator` for research and `DeepCoder` for codebase manipulation and simulation, mirroring the `/boost` and `/teamwork-preview` workflows.

## Workflow

### 1. Research & Dictionary Scraping (DeepInvestigator)
Delegate a `DeepInvestigator` to research the target language on the internet:
1. **Phonetics & Typology:** Determine if the language requires a 1-step phonetic map (e.g., English) or a 2-step Base-Consonant + Modifier state machine (e.g., Hindi, Marathi, Kannada).
2. **Alphabet & Modifiers:** Identify all necessary characters, including essential modifiers (e.g., matras, halants, anusvaras) required to form complete native words without breakage.
3. **Dictionary Building:** Scrape the web or use LLM generation to build a native-script dictionary of at least 1,000 highly common, frequency-sorted words for the AAC context (e.g., family, food, feelings, questions). 

### 2. Codebase Integration (DeepCoder)
Delegate a `DeepCoder` to integrate the researched language into the app:
1. **Dictionary Asset:** Create `app/src/main/assets/[Language]Words.txt` with the 1,000+ generated words.
2. **Settings & UI:** 
   - Add the language to `<string-array name="language_options">` in all localized `arrays.xml` files.
   - Update `dev_mode_display.java` to handle the new selection index.
   - Update `MainActivity.java` `setLocale()` with the correct ISO 639-1 code.
3. **Audio Setup:** Update `AudioManager.java` to set the correct `Locale` and `speechLang` for TTS and Speech-to-Text.

### 3. Predictive Engine & LLM Prompts (DeepCoder)
1. **State Machine (`TextEntryManager.java` & `BlurryInput.java`):**
   - Implement the specific 4-bucket grouping or 2-step base+modifier logic for the language.
   - Ensure the predictive engine triggers immediately (no artificial `characterThreshold`).
2. **Generative LLM (`GemmaManager.kt`):**
   - Inject target-language system prompts for on-device generation.
   - Add 3 native-script few-shot examples (keywords + context).
   - Ensure offline fallbacks correctly parse and append language-specific punctuation without breaking format.

### 4. 10,000+ Iterations Simulation Loop (DeepCoder)
Delegate a `DeepCoder` to build and execute a massive test loop:
1. **Test Harness:** Create or update a headless simulation harness (`simulate.py` or `GemmaLLMSimulationTest.kt`).
2. **Simulation:** Run at least 10,000 macro-iterations feeding generated eye-gesture sequences and LLM context prompts.
   - Validate mathematical prefix collision rates (target: >95% exact matches).
   - Validate context-keyword mapping through the prompt assembly layer (preventing injection errors or messy string truncation).
3. **Feedback Loop:** If errors are found in prediction accuracy or LLM parsing, autonomously correct the thresholds, missing characters, or dictionary mappings, and re-run the simulation until accuracy is perfected.
