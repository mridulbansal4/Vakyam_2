package com.demo.opencv.textEntry;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BlurryInput { // supports languages with alphabet characters: English and Spanish
    private final List<String> wordList = new ArrayList<>(); // top words frequency list
    private final List<String> blurryInputProcessed = new ArrayList<>(); // processed string with 1, 2, 3, and 4 for blurry input
    List<String> extendedWordList = new ArrayList<>(); // extra words provided by OpenAI for context-based prediction
    int characterThreshold = 5; // determines the number of characters before expanding the blurry input to longer words

    public void initialize(Context mContext, String language) {
        wordList.clear();
        blurryInputProcessed.clear();

        if (Objects.equals(language, "English")) {
            characterThreshold = 5;
        } else if (Objects.equals(language, "Spanish")) {
            characterThreshold = 7;
        } else if (Objects.equals(language, "Chinese")) {
            characterThreshold = 4;
        }
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
    }

    public String processWord(String word) { // converts original word to processed word for blurry input
        if (word == null) return "";
        StringBuilder processed = new StringBuilder();
        word = word.toLowerCase();
        word = Normalizer.normalize(word, Normalizer.Form.NFD);
        word = word.replaceAll("[^\\p{ASCII}]", "");

        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c >= 'a' && c <= 'f') { processed.append("0"); } // group 1
            else if (c >= 'g' && c <= 'm') { processed.append("1"); } // group 2
            else if (c >= 'n' && c <= 't') { processed.append("2"); } // group 3
            else if (c >= 'u' && c <= 'z') { processed.append("3"); } // group 4
        }
        return processed.toString();
    }

    public List<String> getMatchingWords(String codeA) { // predict the word based on a blurry input
        List<String> wordPredictions = new ArrayList<>();
        if (codeA == null || codeA.length() == 0) { // the string is empty, nothing to compare
            return wordPredictions;
        }

        // normal 5000 word bank prediction
        for (int i = 0; i < blurryInputProcessed.size(); i++) {
            String codeB = blurryInputProcessed.get(i);
            if (codeB == null) continue;
            boolean match1 = codeA.length() == codeB.length(); // the two words are the same length
            boolean match2 = (codeA.length() < codeB.length()) && codeA.length() > characterThreshold; // A is less than B, but A is more than threshold characters long
            if (match1 || match2) {
                String subString = codeB.substring(0, codeA.length());
                //Log.d("TextPrediction", "Code: " + codeA + ", substring from word: " + subString);
                if (subString.equals(codeA)) {
                    wordPredictions.add(wordList.get(i));
                }
            }
        }

        // extended word bank prediction (smaller, maybe around 500 words max)
        for (int i = 0; i < extendedWordList.size(); i++) {
            String word = extendedWordList.get(i);
            if (word == null) continue;
            String codeB = processWord(word);
            boolean match1 = codeA.length() == codeB.length(); // the two words are the same length
            boolean match2 = (codeA.length() < codeB.length()) && codeA.length() > characterThreshold; // A is less than B, but A is more than threshold characters long
            if (match1 || match2) {
                String subString = codeB.substring(0, codeA.length());
                //Log.d("TextPrediction", "Code: " + codeA + ", substring from word: " + subString);
                if (subString.equals(codeA)) {
                    wordPredictions.add(word);
                }
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
