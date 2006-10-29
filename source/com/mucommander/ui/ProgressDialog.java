
package com.mucommander.ui;

import com.mucommander.job.ExtendedFileJob;
import com.mucommander.job.FileJob;
import com.mucommander.text.SizeFormat;
import com.mucommander.text.Translator;
import com.mucommander.text.DurationFormat;
import com.mucommander.ui.comp.button.ButtonChoicePanel;
import com.mucommander.ui.comp.dialog.FocusDialog;
import com.mucommander.ui.comp.dialog.YBoxPanel;
import com.mucommander.ui.icon.IconManager;
import com.mucommander.conf.ConfigurationManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Vector;

/**
 * This dialog informs the user of the progress made by a FileJob.
 *
 * @author Maxence Bernard
 */
public class ProgressDialog extends FocusDialog implements Runnable, ActionListener {

    private JLabel infoLabel;
    private JLabel statsLabel;

    private JProgressBar totalProgressBar;
    private JProgressBar fileProgressBar;

    private JLabel currentBpsLabel;
    private JLabel elapsedTimeLabel;

    private SpeedGraph speedGraph;

    private ButtonChoicePanel buttonsChoicePanel;
    private JButton pauseResumeButton;
    private JButton stopButton;
//    private JButton hideButton;

    private FileJob job;    
    private Thread repaintThread;

    /* True if the current job is an ExtendedFileJob */
    private boolean dualBar;

    private MainFrame mainFrame;

    private boolean firstTimeActivated = true;

    // Button icons
    private final static String RESUME_ICON = "resume.png";
    private final static String PAUSE_ICON = "pause.png";
    private final static String STOP_ICON = "stop.png";

    // Dialog width is constrained to 320, height is not an issue (always the same)
    private final static Dimension MAXIMUM_DIALOG_DIMENSION = new Dimension(360,10000);
    private final static Dimension MINIMUM_DIALOG_DIMENSION = new Dimension(360,0);

    /** Height allocated to the 'speed graph' */
    private final static int SPEED_GRAPH_HEIGHT = 80;

    /** How often should progress information be refreshed (in ms) */
    private final static int REFRESH_RATE = 1000;


    static {
        // Disable JProgressBar animation which is a real CPU hog under Mac OS X
        UIManager.put("ProgressBar.repaintInterval", new Integer(Integer.MAX_VALUE));
    }


    public ProgressDialog(MainFrame mainFrame, String title) {
        super(mainFrame, title, mainFrame);

        this.mainFrame = mainFrame;

        // Sets maximum and minimum dimensions for this dialog
        setMaximumSize(MAXIMUM_DIALOG_DIMENSION);
        setMinimumSize(MINIMUM_DIALOG_DIMENSION);
    }
    
    
    private void initUI() {
        Container contentPane = getContentPane();

        totalProgressBar = new JProgressBar();
        totalProgressBar.setStringPainted(true);
        totalProgressBar.setAlignmentX(LEFT_ALIGNMENT);
        infoLabel = new JLabel(job.getStatusString());
        infoLabel.setAlignmentX(LEFT_ALIGNMENT);
		
        YBoxPanel yPanel = new YBoxPanel();
        // 2 progress bars
        if (dualBar) {
            yPanel.add(infoLabel);
            fileProgressBar = new JProgressBar();
            fileProgressBar.setStringPainted(true);
            yPanel.add(fileProgressBar);
            yPanel.addSpace(10);
		
            statsLabel = new JLabel(Translator.get("progress_bar.starting"));
            yPanel.add(statsLabel);
			
            // Do not show total progress bar if there is only one file
            // (would show the exact same information as file progress bar)
            if(job.getNbFiles()>1)
                yPanel.add(totalProgressBar);
        }
        // Single progress bar
        else {
            yPanel.add(infoLabel);
            yPanel.add(totalProgressBar);
        }

        yPanel.addSpace(10);
        elapsedTimeLabel = new JLabel(Translator.get("progress_bar.elapsed_time")+": ");
        yPanel.add(elapsedTimeLabel);

        if(dualBar) {
            yPanel.addSpace(10);
            currentBpsLabel = new JLabel(Translator.get("progress_bar.current_speed")+": ");
            yPanel.add(currentBpsLabel);

            yPanel.addSpace(5);
            this.speedGraph = new SpeedGraph();
            speedGraph.setPreferredSize(new Dimension(0, SPEED_GRAPH_HEIGHT));
            yPanel.add(this.speedGraph);
        }

        yPanel.add(Box.createVerticalGlue());
        contentPane.add(yPanel, BorderLayout.CENTER);

        pauseResumeButton = new JButton(Translator.get("pause"), IconManager.getIcon(IconManager.PROGRESS_ICON_SET, PAUSE_ICON));
        pauseResumeButton.addActionListener(this);

        stopButton = new JButton(Translator.get("stop"), IconManager.getIcon(IconManager.PROGRESS_ICON_SET, STOP_ICON));
        stopButton.addActionListener(this);

//        hideButton = new JButton(Translator.get("progress_bar.hide"));
//        hideButton.addActionListener(this);

//        this.buttonsChoicePanel = new ButtonChoicePanel(new JButton[] {pauseResumeButton, stopButton, hideButton}, 0, getRootPane());
        this.buttonsChoicePanel = new ButtonChoicePanel(new JButton[] {pauseResumeButton, stopButton}, 0, getRootPane());
        contentPane.add(buttonsChoicePanel, BorderLayout.SOUTH);

        // Cancel button receives initial focus
        setInitialFocusComponent(stopButton);

        // Enter triggers cancel button
        getRootPane().setDefaultButton(stopButton);
    }


    public void start(FileJob job) {
        this.job = job;
        this.dualBar = job instanceof ExtendedFileJob;
        initUI();
        
        repaintThread = new Thread(this, "com.mucommander.ui.ProgressDialog's Thread");
        repaintThread.start();

    	showDialog();
    }
    

    //////////////////////
    // Runnable methods //
    //////////////////////

    public void run() {
        // Used for dual progress bars only
        ExtendedFileJob extendedJob = null;
        if(dualBar)
            extendedJob = (ExtendedFileJob)job;

        String progressText;
        long lastBytesTotal = 0;
        long lastTime = System.currentTimeMillis();

        while(repaintThread!=null && !job.hasFinished()) {
            // Do not refresh progress information is job is paused, simply sleep
            if(!job.isPaused()) {
                long currentFileRemainingTime = 0;
                long totalRemainingTime;

                long effectiveJobTime = job.getEffectiveJobTime();
                if(effectiveJobTime==0)
                    effectiveJobTime = 1;   // To avoid potential zero divisions

                if (dualBar) {
                    long bytesTotal = extendedJob.getTotalByteCounter().getByteCount();
                    long bytesPerSec = (long)(bytesTotal/(((float)effectiveJobTime)/1000));

                    // Update current file progress bar
                    float filePercentFloat = extendedJob.getFilePercentDone();
                    int filePercentInt = (int)(100*filePercentFloat);
                    fileProgressBar.setValue(filePercentInt);

                    progressText = filePercentInt+"% - ";

                    // Add estimated remaining time (ETA) for current file
                    long currentFileSize = extendedJob.getCurrentFileSize();
                    // If current file size is not available, ETA cannot be calculated
                    if(currentFileSize==-1)
                        progressText += "?";
                    // Avoid potential divisions by zero
                    else if(bytesPerSec==0) {
                        currentFileRemainingTime = -1;
                        progressText += DurationFormat.getInfiniteSymbol();
                    }
                    else {
                        currentFileRemainingTime = (long)((1000*(currentFileSize-extendedJob.getCurrentFileByteCounter().getByteCount()))/(float)bytesPerSec);
                        progressText += DurationFormat.format(currentFileRemainingTime);
                    }

//                    fileProgressBar.setTextOverlay(progressText);
                    fileProgressBar.setString(progressText);

                    // Update stats label
                    statsLabel.setText(
                       Translator.get("progress_bar.transferred",
                                      SizeFormat.format(bytesTotal, SizeFormat.DIGITS_MEDIUM| SizeFormat.UNIT_LONG| SizeFormat.ROUND_TO_KB),
                                      SizeFormat.format(bytesPerSec, SizeFormat.DIGITS_MEDIUM| SizeFormat.UNIT_SHORT| SizeFormat.ROUND_TO_KB))
                    );

                    // Add new immediate bytes per second speed sample to speed graph and label and repaint it
                    long now = System.currentTimeMillis();
                    long currentBps = (long)((bytesTotal-lastBytesTotal)*1000f/(now-lastTime));

                    // Skip this sample if job was paused and resumed, speed would not be accurate
                    if(lastTime>job.getPauseStartDate()) {
                        speedGraph.addSample(currentBps);
                        currentBpsLabel.setText(Translator.get("progress_bar.current_speed")+": "+SizeFormat.format(currentBps, SizeFormat.DIGITS_MEDIUM| SizeFormat.UNIT_SHORT| SizeFormat.ROUND_TO_KB));
                    }

                    lastBytesTotal = bytesTotal;
                    lastTime = now;
                }

                // Update total progress bar
                // Total job percent is based on the *number* of files remaining, not their actual size.
                // So this is very approximate.
                float totalPercentFloat = job.getTotalPercentDone();
                int totalPercentInt = (int)(100*totalPercentFloat);

                totalProgressBar.setValue(totalPercentInt);

                progressText = totalPercentInt+"% - ";

                // Add a rough estimate of the total remaining time (ETA):
                // total remaining time is based on the total job percent completed which itself is based on the *number*
                // of files remaining, not their actual size. So this is very approximate.

                // Avoid potential divisions by zero
                if(totalPercentFloat==0)
                    progressText += "?";
                else {
                    // Make sure that total ETA is never smaller than current file ETA
                    totalRemainingTime = (long)((1-totalPercentFloat)*(effectiveJobTime/totalPercentFloat));
                    totalRemainingTime = Math.max(totalRemainingTime, currentFileRemainingTime);
                    progressText += DurationFormat.format(totalRemainingTime);
                }

//                totalProgressBar.setTextOverlay(progressText);
                totalProgressBar.setString(progressText);

                // Update info label
                infoLabel.setText(job.getStatusString());

                // Update elapsed time label
                elapsedTimeLabel.setText(Translator.get("progress_bar.elapsed_time")+": "+DurationFormat.format(effectiveJobTime));
            }

            // Sleep for a while
            try { Thread.sleep(REFRESH_RATE); }
            catch(InterruptedException e) {}
        }
	
        dispose();
    }

    ////////////////////////////
    // ActionListener methods //
    ////////////////////////////

    public void actionPerformed(ActionEvent e) {
        Object source = e.getSource();
    	
        if (source== stopButton) {
            // Cancel button pressed, dispose dialog and stop job immediately
            // (job will be stopped a second time in windowClosed() but that will just be a no-op)
            dispose();
            job.stop();
        }
        else if(source== pauseResumeButton) {
            boolean isPaused = job.isPaused();

            // Resume the job and change the button's label and icon to 'pause'
            if(isPaused) {
                pauseResumeButton.setText(Translator.get("pause"));
                pauseResumeButton.setIcon(IconManager.getIcon(IconManager.PROGRESS_ICON_SET, PAUSE_ICON));
            }
            // Pause the job and change the button's label and icon to 'resume'
            else {
                pauseResumeButton.setText(Translator.get("resume"));
                pauseResumeButton.setIcon(IconManager.getIcon(IconManager.PROGRESS_ICON_SET, RESUME_ICON));
            }

            // Update buttons mnemonics
            buttonsChoicePanel.updateMnemonics();
            // Pause/resume job
            job.setPaused(!isPaused);
        }
//        else if(source==hideButton) {
//            mainFrame.setState(Frame.ICONIFIED);
//        }
    }


    ///////////////////////////////////////
    // Overridden WindowListener methods // 
    ///////////////////////////////////////

    public void windowActivated(WindowEvent e) {
        // This method is called each time the dialog is activated
        super.windowActivated(e);
        if(firstTimeActivated) {
            if(com.mucommander.Debug.ON) com.mucommander.Debug.trace("first time activated, starting job!");
            firstTimeActivated = false;
            this.job.start();
        }
    }

    public void windowClosed(WindowEvent e) {
        super.windowClosed(e);

        // Stop threads
        repaintThread = null;
        // Job may have already been stopped if cancel button was pressed
        job.stop();
    }



    private class SpeedGraph extends JPanel {

        private final Color BACKGROUND_COLOR = ConfigurationManager.getVariableColor("prefs.colors.background", null);

        private final Color LINE_COLOR = ConfigurationManager.getVariableColor("prefs.colors.selectionBackground", null);

//        private final Color TEXT_COLOR = ConfigurationManager.getVariableColor("prefs.colors.marked", null);

        private final int LINE_SPACING = 6;

        private final int NB_SAMPLES_MAX = 1000;

        private Vector samples = new Vector(NB_SAMPLES_MAX);

        private final int STROKE_WIDTH = 2;

        private Stroke lineStroke = new BasicStroke(STROKE_WIDTH);
        

        private SpeedGraph() {
        }


        private void addSample(long bytesPerSecond) {
//            if(Debug.ON) Debug.trace("bps="+bytesPerSecond);

            // Capacity reached, remove first sample
            if(samples.size()==NB_SAMPLES_MAX)
                samples.removeElementAt(0);

            samples.add(new Long(bytesPerSecond));

            repaint();
        }


        public void paint(Graphics g) {
            Graphics2D g2d = (Graphics2D)g;

            int width = getWidth();
            int height = getHeight();

            g.setColor(BACKGROUND_COLOR);
            g.fillRect(0, 0, width, height);

            g.setColor(LINE_COLOR);
            g2d.setStroke(lineStroke);
            // Enable antialiasing, looks way better
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int nbSamples = samples.size();
            int nbDisplayableLines = (width-2*STROKE_WIDTH)/LINE_SPACING;
            int sampleIndex = nbSamples>nbDisplayableLines?nbSamples-nbDisplayableLines:0;
            int nbLines = Math.min(nbSamples, nbDisplayableLines);

            long maxBps = 0;
            for(int i=sampleIndex; i<sampleIndex+nbLines; i++) {
                long sample = ((Long)samples.elementAt(i)).longValue();
                if(sample>maxBps)
                    maxBps = sample;
            }

            float yRatio = maxBps/((float)height-2*STROKE_WIDTH);

            int x = STROKE_WIDTH;
            for(int l=0; l<nbLines-1; l++) {
                g.drawLine(x, height-STROKE_WIDTH-(int)(((Long)samples.elementAt(sampleIndex)).longValue()/yRatio), (x+=LINE_SPACING), height-STROKE_WIDTH-(int)(((Long)samples.elementAt(++sampleIndex)).longValue()/yRatio));
            }

//            g.setColor(TEXT_COLOR);
//            g.drawString(SizeFormat.format(maxBps, SizeFormat.DIGITS_MEDIUM| SizeFormat.UNIT_SHORT| SizeFormat.ROUND_TO_KB), 10, 10);
        }
    }
}
