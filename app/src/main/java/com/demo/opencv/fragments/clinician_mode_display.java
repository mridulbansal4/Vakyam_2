package com.demo.opencv.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.demo.opencv.UserDataManager;
import com.demo.opencv.metrics.TrialReport;
import com.demo.opencv.metrics.TypingTest;
import com.demo.opencv.metrics.TypingTestPhrases;
import com.demo.opencv.other.ClinicalData;
import com.demo.opencv.R;
import com.demo.opencv.personal.PersonalLexicon;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link clinician_mode_display#newInstance} factory method to
 * create an instance of this fragment.
 */
public class clinician_mode_display extends Fragment {

    Activity mActivity;
    LineChart mChart;
    private TextView typingTestResults, vocabularySummary;
    private final TypingTest.Listener typingTestListener = () -> {
        if (mActivity != null) mActivity.runOnUiThread(this::showTypingResults);
    };
    private static final int WORDS_SHOWN = 25;
    private static final int PHRASES_SHOWN = 8;
    public clinician_mode_display() {
        // Required empty public constructor
    }

    private static float[] FloatListToArray(List<Float> floatList) {
        if (floatList == null) return new float[0];
        synchronized (floatList) {
            float[] floatArray = new float[floatList.size()];
            for (int i = 0; i < floatList.size(); i++) {
                Float val = floatList.get(i);
                floatArray[i] = (val != null) ? val : 0f;
            }
            return floatArray;
        }
    }

    private static String[] StringListToArray(List<String> stringList) {
        if (stringList == null) return new String[0];
        synchronized (stringList) {
            String[] stringArray = new String[stringList.size()];
            for (int i = 0; i < stringList.size(); i++) {
                stringArray[i] = stringList.get(i);
            }
            return stringArray;
        }
    }

    private static ArrayList<Entry> setChartData(float[] floatArray, String[] gazeType) {
        ArrayList<Entry> dataset = new ArrayList<>();
        if (floatArray == null) return dataset;
        for (int i = 0; i < floatArray.length; i++) {
            boolean isClosed = (gazeType != null && gazeType.length > i && Objects.equals(gazeType[i], "Closed"));
            if (!isClosed && floatArray[i] < 1 && floatArray[i] > 0) {
                // only get values in the range
                dataset.add(new Entry(i, floatArray[i]));
            }
        }
        return dataset;
    }

    public static clinician_mode_display newInstance(ClinicalData clinicalData) {
        clinician_mode_display fragment = new clinician_mode_display();
        Bundle args = new Bundle();

        if (clinicalData != null) {
            if (clinicalData.gazeLog != null) {
                args.putStringArray("gazeInputLog", StringListToArray(clinicalData.gazeLog));
            }
            if (clinicalData.leftNICX != null && clinicalData.leftNICY != null) {
                args.putFloatArray("leftNICX", FloatListToArray(clinicalData.leftNICX));
                args.putFloatArray("leftNICY", FloatListToArray(clinicalData.leftNICY));

            }
        }

        fragment.setArguments(args);
        return fragment;
    }

    private static LineDataSet initLineDataSet(ArrayList<Entry> dataset, String label, int color) {
        LineDataSet lineDataSet = new LineDataSet(dataset, label);
        lineDataSet.setDrawIcons(false);
        lineDataSet.enableDashedLine(10f, 5f, 0f);
        lineDataSet.enableDashedHighlightLine(10f, 5f, 0f);
        lineDataSet.setColor(color);
        lineDataSet.setCircleColor(color);
        lineDataSet.setLineWidth(1f);
        lineDataSet.setCircleRadius(1f);
        lineDataSet.setDrawCircleHole(false);
        lineDataSet.setValueTextSize(9f);
        lineDataSet.setDrawFilled(true);
        lineDataSet.setFormLineWidth(1f);
        return lineDataSet;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mActivity = requireActivity();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        TypingTest.removeListener(typingTestListener);
        typingTestResults = null;
        vocabularySummary = null;
    }

    private void setUpTypingTest(View view) {
        typingTestResults = view.findViewById(R.id.typingTestResults);
        view.findViewById(R.id.typingTestStartButton).setOnClickListener(v -> {
            String language = ((UserDataManager) requireActivity().getApplication()).getLanguage();
            String[] own = TypingTestPhrases.forLanguage(language);
            // Language = English auto-detects Hinglish, so its Hinglish phrases are offered too and logged as Hinglish.
            String[] hinglish = "English".equals(language) ? TypingTestPhrases.forLanguage("Hinglish") : new String[0];
            String[] phrases = new String[own.length + hinglish.length];
            System.arraycopy(own, 0, phrases, 0, own.length);
            System.arraycopy(hinglish, 0, phrases, own.length, hinglish.length);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.TypingTest_ChoosePhrase)
                    .setItems(phrases, (dialog, which) -> {
                        TypingTest.arm(phrases[which], which < own.length ? language : "Hinglish");
                        Toast.makeText(requireContext(), R.string.TypingTest_Armed, Toast.LENGTH_LONG).show();
                    })
                    .show();
        });
        view.findViewById(R.id.typingTestShareButton).setOnClickListener(v -> {
            String csv = TypingTest.resultsCsv();
            if (csv.isEmpty()) {
                Toast.makeText(requireContext(), R.string.TypingTest_NoResults, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Vakyam typing tests")
                    .putExtra(Intent.EXTRA_TEXT, csv);
            startActivity(Intent.createChooser(send, getString(R.string.TypingTest_Share)));
        });
        TypingTest.addListener(typingTestListener);
        showTypingResults();
    }

    private void showTypingResults() {
        if (typingTestResults == null || !isAdded()) return;
        typingTestResults.setText(TrialReport.format(TypingTest.results(), getString(R.string.TypingTest_NoResults)));
    }

    private void setUpVocabulary(View view) {
        vocabularySummary = view.findViewById(R.id.vocabularySummary);
        PersonalLexicon lexicon = PersonalLexicon.get(requireContext());
        view.findViewById(R.id.vocabularyForgetButton).setOnClickListener(v -> {
            List<PersonalLexicon.Entry> words = lexicon.wordEntries();
            List<PersonalLexicon.Entry> phrases = lexicon.phraseEntries();
            String[] labels = new String[words.size() + phrases.size()];
            for (int i = 0; i < words.size(); i++) labels[i] = label(words.get(i), false);
            for (int i = 0; i < phrases.size(); i++) labels[words.size() + i] = label(phrases.get(i), true);
            boolean[] chosen = new boolean[labels.length];
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.Vocabulary_ForgetTitle)
                    .setMultiChoiceItems(labels, chosen, (dialog, which, isChecked) -> chosen[which] = isChecked)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.Vocabulary_Forget, (dialog, which) -> {
                        for (int i = 0; i < chosen.length; i++) {
                            if (!chosen[i]) continue;
                            if (i < words.size()) lexicon.forgetWord(words.get(i).getText());
                            else lexicon.forgetPhrase(phrases.get(i - words.size()).getText());
                        }
                        showVocabulary(lexicon);
                    })
                    .show();
        });
        showVocabulary(lexicon);
    }

    private static String label(PersonalLexicon.Entry entry, boolean phrase) {
        return (phrase ? "\u201C" + entry.getText() + "\u201D" : entry.getText()) + " \u00D7" + entry.getCount();
    }

    private void showVocabulary(PersonalLexicon lexicon) {
        if (vocabularySummary == null) return;
        List<PersonalLexicon.Entry> words = lexicon.wordEntries();
        List<PersonalLexicon.Entry> phrases = lexicon.phraseEntries();
        if (words.isEmpty() && phrases.isEmpty()) {
            vocabularySummary.setText(R.string.Vocabulary_Empty);
            return;
        }
        StringBuilder text = new StringBuilder(getString(R.string.Vocabulary_Words, words.size()));
        for (int i = 0; i < words.size() && i < WORDS_SHOWN; i++) {
            text.append(i == 0 ? "\n" : ", ").append(label(words.get(i), false));
        }
        text.append("\n\n").append(getString(R.string.Vocabulary_Phrases, phrases.size()));
        for (int i = 0; i < phrases.size() && i < PHRASES_SHOWN; i++) {
            text.append("\n").append(label(phrases.get(i), true));
        }
        vocabularySummary.setText(text.toString());
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {

        setUpTypingTest(view);
        setUpVocabulary(view);

        mChart = view.findViewById(R.id.chart); // create chart
        if (mChart != null) {
            mChart.setTouchEnabled(true);
            mChart.setPinchZoom(true);
        }

        Bundle args = getArguments();
        if (args != null) {
            Log.d("ClinicalMode", "Received Data");
            float[] leftNICX = args.getFloatArray("leftNICX"); // left NIC x-coordinate
            float[] leftNICY = args.getFloatArray("leftNICY"); // left NIC y-coordinate
            String[] gazeInputLog = args.getStringArray("gazeInputLog"); // gaze type

            if (gazeInputLog != null) { // adding the gaze input log
                Log.d("ClinicalMode", "Gaze input log size = " + gazeInputLog.length);
                TextView gazeInputLogView = view.findViewById(R.id.gazeLogText);
                if (gazeInputLogView != null) {
                    StringBuilder targetString = new StringBuilder();
                    for (String s : gazeInputLog) {
                        targetString.append(s);
                        targetString.append('\n');
                    }
                    gazeInputLogView.setText(targetString.toString());
                }
            }

            if (mChart != null && leftNICX != null && leftNICY != null) { // if data present, adding graph
                ArrayList<Entry> dataset1 = setChartData(leftNICX, gazeInputLog); // convert to data type
                ArrayList<Entry> dataset2 = setChartData(leftNICY, gazeInputLog);

                LineDataSet set1, set2; // define data sets
                set1 = initLineDataSet(dataset1, "NIC left x-coordinate", 0xFF2F6F6A);
                set2 = initLineDataSet(dataset2, "NIC left y-coordinate", 0xFFB5563F);

                ArrayList<ILineDataSet> dataSets = new ArrayList<>(); // combine the data sets
                dataSets.add(set1);
                dataSets.add(set2);
                LineData data = new LineData(dataSets);
                mChart.setData(data); // set data to the graph
                YAxis yAxis = mChart.getAxisLeft();
                yAxis.setAxisMinimum(0f);
                yAxis.setAxisMaximum(1f);
                yAxis.setGranularity(0.1f);
                mChart.invalidate();
            } else {
                Log.d("ClinicalMode",  "Data incomplete or missing");
            }
        } else {
            Log.d("ClinicalMode", "No data received");
        }

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.clinician_mode_fragment, container, false);
    }
}