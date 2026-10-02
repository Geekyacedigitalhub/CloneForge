package com.cloneforge.fixture;

import android.app.Activity;
import android.os.Bundle;

public final class FixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("CloneForge Fixture");
    }

    public Class<?> fixtureType() {
        return FixtureActivity.class;
    }
}
