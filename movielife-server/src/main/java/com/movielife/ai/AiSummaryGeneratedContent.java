package com.movielife.ai;

import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Value
public class AiSummaryGeneratedContent {
    String summary;
    List<String> pros;
    List<String> cons;
    List<String> suitableFor;
    List<String> warningPoints;

    public AiSummaryGeneratedContent(String summary,
                                     List<String> pros,
                                     List<String> cons,
                                     List<String> suitableFor,
                                     List<String> warningPoints) {
        this.summary = summary;
        this.pros = Collections.unmodifiableList(new ArrayList<>(pros));
        this.cons = Collections.unmodifiableList(new ArrayList<>(cons));
        this.suitableFor = Collections.unmodifiableList(new ArrayList<>(suitableFor));
        this.warningPoints = Collections.unmodifiableList(new ArrayList<>(warningPoints));
    }
}
