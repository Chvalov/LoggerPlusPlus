package com.nccgroup.loggerplusplus.grepper;

import burp.IHttpRequestResponse;
import burp.IHttpRequestResponseWithMarkers;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.logentry.LogEntry;
import com.nccgroup.loggerplusplus.logview.logtable.LogTableController;
import com.nccgroup.loggerplusplus.preferences.PreferencesController;
import com.nccgroup.loggerplusplus.util.Globals;
import com.nccgroup.loggerplusplus.util.NamedThreadFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class GrepperController {

    private final LoggerPlusPlus loggerPlusPlus;
    private final LogTableController logTableController;
    private final Preferences preferences;
    private final GrepperPanel grepPanel;
    private final ArrayList<GrepperListener> listeners;
    private final AtomicInteger remainingEntries;

    private ExecutorService searchExecutor;

    public GrepperController(LoggerPlusPlus loggerPlusPlus, LogTableController logTableController, PreferencesController preferencesController){
        this.loggerPlusPlus = loggerPlusPlus;
        this.logTableController = logTableController;
        this.preferences = preferencesController.getPreferences();
        this.listeners = new ArrayList<>();
        this.remainingEntries = new AtomicInteger(0);
        this.grepPanel = new GrepperPanel(this, preferences);
    }

    public LoggerPlusPlus getLoggerPlusPlus() {
        return loggerPlusPlus;
    }

    public LogTableController getLogTableController() {
        return logTableController;
    }

    public GrepperPanel getGrepperPanel() {
        return grepPanel;
    }

    public boolean isSearching(){
        return remainingEntries.get() > 0;
    }

    public void reset() { //TODO SwingWorker
        for (GrepperListener listener : this.listeners) {
            try {
                listener.onResetRequested();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public IHttpRequestResponseWithMarkers addMarkers(IHttpRequestResponse requestResponse, List<GrepResults.Match> matches) {
        List<int[]> requestMarkers = new ArrayList<>(), responseMarkers = new ArrayList<>();
        for (GrepResults.Match match : matches) {
            int[] marker = new int[]{match.startIndex, match.endIndex};
            if (match.isRequest) requestMarkers.add(marker);
            else responseMarkers.add(marker);
        }

        return LoggerPlusPlus.callbacks.applyMarkers(requestResponse, requestMarkers, responseMarkers);
    }

    public void beginSearch(final Pattern pattern, final boolean inScopeOnly) {
        int searchThreads = this.preferences.getSetting(Globals.PREF_SEARCH_THREADS);
        this.searchExecutor = Executors.newFixedThreadPool(searchThreads, new NamedThreadFactory("LPP-Grepper"));

        new Thread(() -> {
            ArrayList<LogEntry> logEntries = new ArrayList<>(loggerPlusPlus.getLogViewController().getLogTableController().getLogTableModel().getData());
            remainingEntries.getAndSet(logEntries.size());

            this.listeners.forEach(listener -> {
                listener.onSearchStarted(pattern, logEntries.size());
            });

            for (LogEntry logEntry : logEntries) {
                searchExecutor.submit(createProcessThread(logEntry, pattern, inScopeOnly));
            }
        }).start();
    }

    private Runnable createProcessThread(final LogEntry logEntry, final Pattern pattern, final boolean inScopeOnly){
        return () -> {
            if(Thread.currentThread().isInterrupted()) return;
            GrepResults grepResults = null;
            if (!inScopeOnly || LoggerPlusPlus.callbacks.isInScope(logEntry.url)) {
                grepResults = new GrepResults(pattern, logEntry);
            }
            for (GrepperListener listener : this.listeners) {
                try {
                    listener.onEntryProcessed(grepResults);
                }catch (Exception e){
                    e.printStackTrace();
                }
            }
            int remaining = remainingEntries.decrementAndGet();
            if(remaining == 0){
                for (GrepperListener listener : listeners) {
                    try {
                        listener.onSearchComplete();
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
            }
        };
    }

    public void cancelSearch(){
        new Thread(() -> {
            for (GrepperListener listener : listeners) {
                listener.onShutdownInitiated();
            }

            searchExecutor.shutdownNow();
            while (!searchExecutor.isTerminated()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            for (GrepperListener listener : listeners) {
                try {
                    listener.onShutdownComplete();
                }catch (Exception e){
                    e.printStackTrace();
                }
            }

            remainingEntries.set(0);
        }).start();

    }

    public void addListener(GrepperListener listener){
        synchronized (this.listeners) {
            this.listeners.add(listener);
        }
    }

    public void removeListener(GrepperListener listener){
        synchronized (this.listeners){
            this.listeners.remove(listener);
        }
    }
}
