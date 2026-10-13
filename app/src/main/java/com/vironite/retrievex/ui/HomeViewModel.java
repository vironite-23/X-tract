package com.vironite.retrievex.ui;

import androidx.lifecycle.ViewModel;

import com.vironite.retrievex.extract.PostInfo;

/** Survives configuration changes so the fetched post and selection are not lost on rotation. */
public class HomeViewModel extends ViewModel {
    public PostInfo post;
    public boolean[] selected;
    public String error;
    public boolean loading;
    public int generation;
}
