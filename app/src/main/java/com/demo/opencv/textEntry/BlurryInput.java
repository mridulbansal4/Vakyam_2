package com.demo.opencv.textEntry;

import android.content.Context;
import android.util.Log;

import com.demo.opencv.personal.PersonalLexicon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;

public class BlurryInput {
    private final List<String> wordList = new ArrayList<>(); // top words frequency list
    private final List<String> blurryInputProcessed = new ArrayList<>(); // processed string with 1, 2, 3, and 4 for blurry input
    private final List<String> hinglishWordList = new ArrayList<>(); // Roman-script Hindi, offered alongside English
    private final List<String> hinglishProcessed = new ArrayList<>();
    // Answer words for what the caregiver just said; replaced whole, never edited, as the LLM fills it from another thread
    volatile List<String> extendedWordList = new ArrayList<>();
    private PersonalLexicon personalLexicon; // the patient's own words, null until set
    private final PersonalLexicon.Script script = PersonalLexicon.Script.LATIN; // older Devanagari entries stay hidden
    private String languageSetting = HinglishDetector.ENGLISH;
    private HinglishDetector hinglishDetector = new HinglishDetector(HinglishLexicon.EMPTY);
    private String lastSpokenLanguage = HinglishDetector.ENGLISH; // decides the dictionary order before a word is typed

    public interface AssetSource {
        InputStream open(String name) throws IOException;
    }

    private static class BigramEntry {
        String word;
        int frequency;
        BigramEntry(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
    }
    private final Map<String, List<BigramEntry>> bigramMap = new HashMap<>();

    public void initialize(Context mContext, String language) {
        if (mContext == null) {
            initialize(language, null, HinglishLexicon.EMPTY);
            return;
        }
        Context appContext = mContext.getApplicationContext() != null ? mContext.getApplicationContext() : mContext;
        initialize(language, name -> appContext.getAssets().open(name), HinglishLexicon.get(appContext));
    }

    void initialize(String language, AssetSource assets, HinglishLexicon hinglish) {
        wordList.clear();
        blurryInputProcessed.clear();
        hinglishWordList.clear();
        hinglishProcessed.clear();
        bigramMap.clear();
        languageSetting = language;
        hinglishDetector = new HinglishDetector(hinglish);
        if (assets == null) return;
        // Both languages type on the same English letters; the setting only decides which words come first
        loadWords(assets, HinglishLexicon.ENGLISH_ASSET, wordList, blurryInputProcessed);
        loadBigrams(assets, "EnglishBigrams.txt");
        for (String word : hinglish.getWords()) {
            hinglishWordList.add(word);
            hinglishProcessed.add(processWord(word));
        }
        loadBigrams(assets, HinglishLexicon.BIGRAMS_ASSET);
    }

    private void loadWords(AssetSource assets, String name, List<String> words, List<String> processed) {
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(assets.open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                words.add(line);
                processed.add(processWord(line)); // process word
            }
        } catch (Exception e) {
            Log.w("BlurryInput", "word list unavailable: " + name, e);
        }
    }

    private void loadBigrams(AssetSource assets, String name) {
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(assets.open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    List<BigramEntry> nextWords = bigramMap.get(parts[0]);
                    if (nextWords == null) {
                        nextWords = new ArrayList<>();
                        bigramMap.put(parts[0], nextWords);
                    }
                    nextWords.add(new BigramEntry(parts[1], Integer.parseInt(parts[2].trim())));
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public void setPersonalLexicon(PersonalLexicon personalLexicon) {
        this.personalLexicon = personalLexicon;
    }

    public void setLastSpokenLanguage(String language) {
        this.lastSpokenLanguage = language;
    }

    public void setContextWords(List<String> words) {
        extendedWordList = (words == null) ? new ArrayList<>() : new ArrayList<>(words);
    }

    public List<String> getContextWords() {
        return extendedWordList;
    }

    // The language the patient is typing in gets two of every three dictionary slots, the other language one.
    private void addDictionaryMatches(List<String> englishMatches, List<String> hinglishMatches, List<String> currentWords, List<String> wordPredictions) {
        boolean hinglishFirst = HinglishDetector.HINGLISH.equals(
                hinglishDetector.resolve(languageSetting, currentWords, null, lastSpokenLanguage));
        Set<String> alreadyOffered = new HashSet<>(wordPredictions);
        englishMatches.removeAll(alreadyOffered);
        hinglishMatches.removeAll(alreadyOffered);
        List<String> merged = hinglishFirst
                ? HinglishLexicon.interleave(hinglishMatches, englishMatches)
                : HinglishLexicon.interleave(englishMatches, hinglishMatches);
        wordPredictions.addAll(merged);
    }

    private static List<String> codeMatches(List<String> processed, List<String> words, String codeA) {
        Set<String> matches = new LinkedHashSet<>();
        for (int i = 0; i < processed.size(); i++) {
            String codeB = processed.get(i);
            if (codeB != null && codeB.startsWith(codeA) && words.get(i) != null) matches.add(words.get(i));
        }
        return new ArrayList<>(matches);
    }

    private static List<String> prefixMatches(List<String> words, String prefix) {
        Set<String> matches = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && word.startsWith(prefix)) matches.add(word);
        }
        return new ArrayList<>(matches);
    }

    // Words the patient has typed straight after the last word, then nothing else: their own habits outrank the generic bigrams.
    private List<String> personalFollowers(List<String> currentWords) {
        if (personalLexicon == null || currentWords == null || currentWords.isEmpty()) return new ArrayList<>();
        return personalLexicon.followers(currentWords.get(currentWords.size() - 1), script);
    }

    private List<String> personalWords() {
        return personalLexicon == null ? new ArrayList<>() : personalLexicon.words(script);
    }

    private void addCodeMatches(List<String> candidates, String codeA, List<String> wordPredictions) {
        for (String word : candidates) {
            if (processWord(word).startsWith(codeA) && !wordPredictions.contains(word)) {
                wordPredictions.add(word);
            }
        }
    }

    private void addPrefixMatches(List<String> candidates, String prefix, List<String> wordPredictions) {
        for (String word : candidates) {
            if (word.startsWith(prefix) && !wordPredictions.contains(word)) {
                wordPredictions.add(word);
            }
        }
    }

    public String processWord(String word) { // converts original word to processed word for blurry input
        if (word == null) return "";
        StringBuilder processed = new StringBuilder();
        word = word.toLowerCase();
        word = Normalizer.normalize(word, Normalizer.Form.NFD);
        // Retain ASCII and Devanagari characters
        word = word.replaceAll("[^a-z\\u0900-\\u097F]", "");

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if ((c >= 'a' && c <= 'f') || (c >= '\u0900' && c <= '\u091F')) { processed.append("0"); } // group 1
            else if ((c >= 'g' && c <= 'm') || (c >= '\u0920' && c <= '\u093F')) { processed.append("1"); } // group 2
            else if ((c >= 'n' && c <= 't') || (c >= '\u0940' && c <= '\u095F')) { processed.append("2"); } // group 3
            else if ((c >= 'u' && c <= 'z') || (c >= '\u0960' && c <= '\u097F')) { processed.append("3"); } // group 4
        }
        return processed.toString();
    }

    public List<String> getMatchingWords(String codeA, List<String> currentWords) { // predict the word based on a blurry input
        List<String> wordPredictions = new ArrayList<>();
        if (codeA == null || codeA.length() == 0) { // the string is empty, nothing to compare
            return wordPredictions;
        }

        addCodeMatches(personalFollowers(currentWords), codeA, wordPredictions);

        // The caregiver's question outranks generic word frequency: typing "r" after "what do you want to eat" offers rice
        addCodeMatches(extendedWordList, codeA, wordPredictions);

        // bigram map prediction based on context
        if (currentWords != null && !currentWords.isEmpty()) {
            String lastWord = currentWords.get(currentWords.size() - 1).toLowerCase();
            List<BigramEntry> nextWords = bigramMap.get(lastWord);
            if (nextWords != null) {
                nextWords.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
                for (BigramEntry entry : nextWords) {
                    String word = entry.word;
                    if (word == null) continue;
                    String codeB = processWord(word);
                    if (codeB.length() >= codeA.length()) { // any prefix match
                        String subString = codeB.substring(0, codeA.length());
                        if (subString.equals(codeA)) {
                            if (!wordPredictions.contains(word)) {
                                wordPredictions.add(word);
                            }
                        }
                    }
                }
            }
        }

        addCodeMatches(personalWords(), codeA, wordPredictions);

        // normal 5000 word bank prediction, interleaved with the Hinglish words
        addDictionaryMatches(codeMatches(blurryInputProcessed, wordList, codeA),
                codeMatches(hinglishProcessed, hinglishWordList, codeA), currentWords, wordPredictions);
        return wordPredictions;
    }

    public List<String> getExactMatchingWords(String prefix, List<String> currentWords) {
        List<String> wordPredictions = new ArrayList<>();
        if (prefix == null) return wordPredictions;

        addPrefixMatches(personalFollowers(currentWords), prefix, wordPredictions);

        // With letters typed the question decides; with none, the next word is grammar, so bigrams lead
        List<String> contextWords = extendedWordList;
        if (!prefix.isEmpty()) addPrefixMatches(contextWords, prefix, wordPredictions);

        if (currentWords != null && !currentWords.isEmpty()) {
            String lastWord = currentWords.get(currentWords.size() - 1).toLowerCase();
            List<BigramEntry> nextWords = bigramMap.get(lastWord);
            if (nextWords != null) {
                nextWords.sort((a, b) -> Integer.compare(b.frequency, a.frequency));
                for (BigramEntry entry : nextWords) {
                    String word = entry.word;
                    if (word != null && word.startsWith(prefix) && !wordPredictions.contains(word)) {
                        wordPredictions.add(word);
                    }
                }
            }
        }

        addPrefixMatches(personalWords(), prefix, wordPredictions);
        if (prefix.isEmpty()) addPrefixMatches(contextWords, prefix, wordPredictions);
        addDictionaryMatches(prefixMatches(wordList, prefix), prefixMatches(hinglishWordList, prefix), currentWords, wordPredictions);
        return wordPredictions;
    }

    public String[] getWordPage(List<String> wordPredictions, int page, int wordsPerPage) { // gets the word prediction of a page with a certain number of words
        // page begins from 1
        if (wordPredictions == null || wordPredictions.isEmpty() || page < 1 || wordsPerPage <= 0) return null;
        int wordsPredicted = wordPredictions.size();
        if ((page - 1) * wordsPerPage >= wordsPredicted) { // if the page doesn't exist (page 3, 3 words per page, 7 words detected)
            return null;
        } else {
            String[] wordArray = new String[wordsPerPage];
            for (int i = 1; i <= wordsPerPage; i++) {
                if ((page - 1) * wordsPerPage + i <= wordsPredicted) {
                    wordArray[i - 1] = wordPredictions.get((page - 1) * wordsPerPage + i - 1);
                }
            }
            return wordArray;
        }
    }
}
