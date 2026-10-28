package com.interlink.app;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.interlink.app.model.SessionState;
import com.interlink.app.network.SessionManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class IntercomIntegrationTest {

    @Test
    public void testSessionManagerInitialization() {
        Context context = ApplicationProvider.getApplicationContext();
        assertNotNull(context);

        SessionManager manager = SessionManager.getInstance(context);
        assertNotNull(manager);
        assertEquals(SessionState.IDLE, manager.getCurrentState());
    }
}

