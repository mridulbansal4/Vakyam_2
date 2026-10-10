package com.demo.opencv.textEntry;

import android.content.Context;
import android.util.Log;

import com.demo.opencv.personal.PersonalLexicon;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BlurryInput { // supports languages with alphabet characters: English and Spanish
    private final List<String> wordList = new ArrayList<>(); // top words frequency list
    private final List<String> blurryInputProcessed = new ArrayList<>(); // processed string with 1, 2, 3, and 4 for blurry input
    List<String> extendedWordList = new ArrayList<>(); // extra words provided by OpenAI for context-based prediction
    private PersonalLexicon personalLexicon; // the patient's own words, null until set
    private PersonalLexicon.Script script = PersonalLexicon.Script.LATIN;

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
        wordList.clear();
        blurryInputProcessed.clear();
        bigramMap.clear();
        script = PersonalLexicon.Script.forLanguage(language);
        if (mContext == null) return;
        try {
            InputStream inputStream = mContext.getAssets().open(language + "Words.txt"); // set the correct language
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            while ((line = bufferedReader.readLine()) != null) {
                wordList.add(line);
                blurryInputProcessed.add(processWord(line)); // process word
            }
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            InputStream inputStream = mContext.getAssets().open(language + "Bigrams.txt");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    List<BigramEntry> nextWords = bigramMap.get(parts[0]);
                    if (nextWords == null) {
                        nextWords = new ArrayList<>();
                        bigramMap.put(parts[0], nextWords);
                    }
                    nextWords.add(new BigramEntry(parts[1], Integer.parseInt(parts[2])));
                }
            }
            inputStream.close();
        } catch (Exception e) {
            // ignore
        }
    }

    public void setPersonalLexicon(PersonalLexicon personalLexicon) {
        this.personalLexicon = personalLexicon;
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

        // extended word bank prediction (smaller, maybe around 500 words max)
        for (int i = 0; i < extendedWordList.size(); i++) {
            String word = extendedWordList.get(i);
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

        // normal 5000 word bank prediction
        for (int i = 0; i < blurryInputProcessed.size(); i++) {
            String codeB = blurryInputProcessed.get(i);
            if (codeB == null) continue;
            if (codeB.length() >= codeA.length()) { // any prefix match
                String subString = codeB.substring(0, codeA.length());
                if (subString.equals(codeA)) {
                    String word = wordList.get(i);
                    if (!wordPredictions.contains(word)) {
                        wordPredictions.add(word);
                    }
                }
            }
        }
        return wordPredictions;
    }

    public List<String> getExactMatchingWords(String prefix, List<String> currentWords) {
        List<String> wordPredictions = new ArrayList<>();
        if (prefix == null) return wordPredictions;

        addPrefixMatches(personalFollowers(currentWords), prefix, wordPredictions);

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
        for (String word : extendedWordList) {
            if (word != null && word.startsWith(prefix) && !wordPredictions.contains(word)) {
                wordPredictions.add(word);
            }
        }
        for (String word : wordList) {
            if (word != null && word.startsWith(prefix) && !wordPredictions.contains(word)) {
                wordPredictions.add(word);
            }
        }
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
