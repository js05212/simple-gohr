package edu.wisc.game.sql;

import java.io.*;
import java.util.*;
import javax.persistence.*;

import org.apache.openjpa.persistence.jdbc.*;

import jakarta.xml.bind.annotation.XmlElement; 

import edu.wisc.game.util.*;
import edu.wisc.game.parser.*;
import edu.wisc.game.rest.ParaSet;
import edu.wisc.game.rest.TrialList;
import edu.wisc.game.rest.Files;
import edu.wisc.game.engine.RuleSet;
import edu.wisc.game.engine.AllRuleSets;
import edu.wisc.game.saved.*;

/** A PlayerInfo object represent information about a player (what trial list he's in, what episodes he's done etc) stored in the SQL database. It is identiied by a playerId (a string). A humans playing the Rule Game may create just one PlayerInfo object (a single playerId), if he comes from the Mechanical Turk, or goes directly to the GUI Client URL; or he can create many such objects (each one with a particular experiment plan), if he starts many games from the Repeat User Launch page, or from the Android app. In the latter case, all such PlayerInfo objects are linked to a single User object.
 */

@Entity  
public class PlayerInfo {
    @Id 
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private long id;
    public long getId() { return id;}
    
    /** The date of first activity */
    @Basic
    private Date date; 

    /** Back link to the user, for JPA's use. It is non-null only for
	player IDs created via the repeat-user launch pages, or via the
	Android app.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    private User user;
    public User getUser() { return user; }
    public void setUser(User _user) { user = _user; }


    @Basic 
    private String playerId;
    /** A human-readable string ID for this player. A mandatory
	non-null value, which we strive to keep unique (by the
	PlayerResponse code).  */
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String _playerId) { playerId = _playerId; }

    @Basic 
    private String experimentPlan;
    /** The experiment plan historically was just a directory name, 
	e.g. "pilot06". Starting from ver. 3.004, dynamic experiment 
	plans are also supported, in the form P:plan:modifer or
	R:ruleSet:modifier.
     */
    public String getExperimentPlan() { return experimentPlan; }
    public void setExperimentPlan(String _experimentPlan) { experimentPlan = _experimentPlan; }

    
    @Basic 
    private String trialListId;
    /** For traditional (static) and "P:"-type dynamic experiment
	plans, this is the name of the actual trial list file in the
	appropriate experiment plan directory (without the ".csv"
	extension). For "R:"-type dynamic experiment plans (which do
	not involve any trial list files) this field, rather tautologically,
	contains the rule set name.
     */
    public String getTrialListId() { return trialListId; }
    public void setTrialListId(String _trialListId) { trialListId = _trialListId; }
    public Date getDate() { return date; }
    public void setDate(Date _date) { date = _date; }

    /** FIXME: this may result in an Episode being persisted before its completed, 
	which we, generally, don't like. Usually not a big deal though.
     */
    @OneToMany(
        mappedBy = "player",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
	fetch = FetchType.EAGER)
    private Vector<EpisodeInfo> allEpisodes = new  Vector<>();

    public void addEpisode(EpisodeInfo c) {
        allEpisodes.add(c);
        c.setPlayer(this);
    }
 
    public void removeEpisode(EpisodeInfo c) {
        allEpisodes.remove(c);
        c.setPlayer(null);
    }
    
    public Vector<EpisodeInfo> getAllEpisodes() { return allEpisodes; }
    public void setAllEpisodes(Vector<EpisodeInfo> _allEpisodes) {
	for(EpisodeInfo p: allEpisodes) p.setPlayer(null);
	allEpisodes.setSize(0);
	for(EpisodeInfo p: _allEpisodes) addEpisode(p);    	
    }
   
    //public static String assignTrialList(String player) {
    //	return null;
    //    }

    public String toString() {
	return "(PlayerInfo: id=" + id +",  playerId="+ playerId+", trialListId=" + trialListId +", date=" + date+")";
    }


    /** A Series is a list of all episodes played under a specific param set. A player
	has as many Series objects as there are lines in that player's trial list.
     */
    class Series {
	final ParaSet para;
	final GameGenerator gg;
	Vector<EpisodeInfo> episodes = new Vector<>();

	Series(ParaSet _para) throws IOException, IllegalInputException, ReflectiveOperationException, RuleParseException {
	    para = _para;
	    gg = GameGenerator.mkGameGenerator(Episode.random, para);
	}
	
	public String toString() {
	    return "(Series: para.rules=" +	para.getRuleSetName() +
		", episode cnt= "+ episodes.size()+")";
	}

	/** How many bonus episodes (completed or not) this user has
	    completed or is still playing? */
	int countBonusEpisodes() {
	    int cnt=0;
	    for(EpisodeInfo x: episodes) {
		if (x.bonus) cnt++;
	    }
	    return cnt;
	}

	/** Checks if this player has earned a bonus in this series, and if so, 
	    attaches it to an appropriate episode, and ends this series */
	private void assignBonus() {
	    int cnt=0, deserveCnt=0;
	    for(EpisodeInfo x: episodes) {
		if (x.bonus) {
		    cnt++;
		    if (x.bonusSuccessful)  deserveCnt++;
		}
	    }
	    if (cnt==deserveCnt && deserveCnt>=para.getInt("clear_how_many")) {
		// bonus earned; attach it to the last episode in the bonus series,
		// and end the series
		int r = 0;
		for(EpisodeInfo x: episodes) {
		    if (x.bonusSuccessful) {		       
			r++;
			x.earnedBonus = (r==cnt);
			x.rewardBonus= (x.earnedBonus)? para.getInt("bonus_extra_pts"):0;
		    }
		}
	    }
	}

	boolean bonusHasBeenEarned() {
	    for(EpisodeInfo x: episodes) {
		if (x.earnedBonus) return true;
	    }
	    return false;
	}

	/** Scans the episodes of the series to see if the xFactor has
	    been set for this series. Only appliable to series with 
	    DOUBLING incentive scheme.
	    @return 1,2, or 4.
	 */
	int findXFactor() {
	    int f = 1;
	    for(EpisodeInfo x: episodes) {
		if (x.getXFactor()>f) f=x.getXFactor();
	    }
	    return f;
	}

	/** True if we have the DOUBLING incentive scheme, and this 
	    series has been ended by the x4 achievement */
	boolean seriesEndedByX4() {
	    return para.getIncentive()==ParaSet.Incentive.DOUBLING &&
		findXFactor()==4;
	}

	
    }


    Series getSeries(int k) {
	return allSeries.get(k);
    }
    
    /** Retrieves a link to the currently played series, or null if this player
	has finished all his series */
    Series getCurrentSeries() {
	return alreadyFinished()? null: allSeries.get(currentSeriesNo);
    }

    /** Returns true if the current series number is set beyond the possible
	range, which indicates that it has gone through the last possible
	increment (and, therefore, the completion code has been set as well).
    */
    public boolean alreadyFinished() {
	return currentSeriesNo>=allSeries.size();
    }
    
    /** Based on the current situation, what is the maximum number
	of episodes that can be run within the current series? 
	(Until max_boards is reached, if in the main subseries, 
	or until the bonus is earned, if in the bonus subseries).
    */
    int totalBoardsPredicted() {
	Series ser = getCurrentSeries();	
	return ser==null? 0:
	    inBonus? ser.episodes.size() +    ser.para.getInt("clear_how_many")-
	    countBonusEpisodes(currentSeriesNo):	    
	    getCurrentSeries().para.getMaxBoards();
    }

    
    /** @return true if an "Activate Bonus" button can be displayed, i.e. 
	the player is eligible to start bonus episodes, but has not done that yet */
    public boolean canActivateBonus() {
	Series ser = getCurrentSeries();	
	if (ser.para.getIncentive()!=ParaSet.Incentive.BONUS) return false;
	if (inBonus) return false;  // already doing a bonus subseries!
	if (ser==null) return false;
	int at = ser.para.getInt("activate_bonus_at");
	// 0-based index of the episode on which activation will be in
	// effect, if it happens
	int nowAt = ser.episodes.size();
	if (ser.episodes.size()>0) {
	    if (!ser.episodes.lastElement().isCompleted()) nowAt--;
	}
	// 1-based
	nowAt++;

	boolean answer = (nowAt >= at);
	//System.err.println("canActivateBonus("+playerId+")="+answer+", for series No. "+currentSeriesNo+", size="+ser.episodes.size()+", at="+at);
	return answer;
    } 

    /** Switches this player from the main subseries to the bonus subseries, and
	saves the information about this fact in the SQL server.
	@param em The active EM to use. (We have this because this
	method is called from a method that has an EM anyway, and this
	object is NOT detached.)
    */
    public void activateBonus(EntityManager em) {
	if (inBonus) throw new IllegalArgumentException("Bonus already activated in the current series");
	
	if (!canActivateBonus()) throw new IllegalArgumentException("Cannot activate bonus in the current series");
	
	inBonus = true;

	Series ser=getCurrentSeries();
	if (ser!=null && ser.episodes.size()>0) {
	    EpisodeInfo epi = ser.episodes.lastElement();
	    // if the current episode is still running, make it a bonus episode too
	    if (!epi.isCompleted()) {
		epi.bonus=true;
		System.err.println("Bonus activated for current episode " + epi.episodeId);
	    }
	}
	System.err.println("Bonus activated: player="+playerId+", series No. "+currentSeriesNo+", size="+ser.episodes.size());
	// this saves the new value of inBonus
	em.getTransaction().begin();	    
	em.getTransaction().commit();	        
    }

    /** "Gives up" he current series, i.e. immediately switches the
	player to the next series (if there is one). */
    public void giveUp(int seriesNo) throws IOException {
	Logging.info("giveUp(pid="+playerId+", seriesNo=" + seriesNo +"), currentSeriesNo=" +currentSeriesNo);
	if (seriesNo+1==currentSeriesNo) {	    
	    // that series has just ended anyway...
	    Logging.info("giveUp: ignorable call on the previous series");
	    return;
	}
	if (seriesNo!=currentSeriesNo) throw new IllegalArgumentException("Cannot give up on series " + seriesNo +", because we presently are on series " + currentSeriesNo);
	if (seriesNo>=allSeries.size())  throw new IllegalArgumentException("Already finished all "+allSeries.size()+" series");
	Series ser=getCurrentSeries();
	if (ser!=null || ser.episodes.size()>0) {
	    EpisodeInfo epi = ser.episodes.lastElement();
	    // give up on the currently active episode, if any
	    if (!epi.isCompleted()) {
		epi.giveUp();
		Logging.info("giveUp: episodeId=" + epi.getEpisodeId()+", set givenUp=" + epi.givenUp);
		//Main.persistObjects(epi);
		// Persists SQL, and write CSV
		ended(epi);
	    }
	}
		   	
	goToNextSeries();
	Logging.info("giveUp completed, now currentSeriesNo=" +currentSeriesNo);

    }


    /** Can a new "regular" (non-bonus) episode be started in the current series? */
    private boolean canHaveAnotherRegularEpisode() {
	Series ser=getCurrentSeries();
	if (ser==null) return false;
	if (ser.seriesEndedByX4()) return false;
	if (inBonus) return false;
	return  ser.episodes.size()<ser.para.getMaxBoards();
    } 

    /** Can a new bonus episode be started in the current series? */
    private boolean canHaveAnotherBonusEpisode() {
	System.err.println("canHaveAnotherBonusEpisode("+playerId+",ser="+currentSeriesNo+")? inBonus="+inBonus);
	Series ser=getCurrentSeries();
	if (ser==null) return false;
	System.err.println("ser=" + ser+", earned=" +  ser.bonusHasBeenEarned());
	if (!inBonus || ser.bonusHasBeenEarned()) return false;
	int cnt=0;
	System.err.println("Have " +  ser.episodes.size() + " episodes to look at");
	for(EpisodeInfo x: ser.episodes) {
	    System.err.println("looking at "+(x.isBonus()? "bonus":"main")+
			       " episode " + x.episodeId + ", completed=" + x.isCompleted());
	    if (x.isBonus()) {
		cnt++;
		if (!x.isCompleted()) return false;
		if (!x.bonusSuccessful) return false;
		System.err.println("ok bonus episode " + x.episodeId);
	    } 
	}
	boolean result = cnt<ser.para.getInt("clear_how_many");
	System.err.println("cnt=" + cnt+", allowed up to " + ser.para.getInt("clear_how_many") +", result=" + result);
	return result;
    }

    /** The main table for all episodes of this player, arranged in
	series.  This table normally contains entries for all series,
	both those already played and the future ones, one series per
	para set. The tables is initialized by initSeries().
     */
    @Transient
    private Vector<Series> allSeries = new Vector<>();

    /** How many episodes are currently in series No. k? */
    public int seriesSize(int k) {
	return allSeries.get(k).episodes.size();
    }

    /** How many bonus episodes (complete or not) are currently in series No. k? */
    public int countBonusEpisodes(int k) {
	Series ser= allSeries.get(k);
	int cnt=0;
	for(EpisodeInfo x: ser.episodes) {
	    if (x.isBonus())  cnt++;
	}
	return cnt;
    }

    
    /** What series will the next episode be a part of? (Or, if the current episode
	is not completed, what series is it a part of?) */
    private int currentSeriesNo=0;
    public int getCurrentSeriesNo() { return currentSeriesNo; }

    /** Will the next episode be a part of a bonus subseries? (Or, if the current 
	episode is not completed, is it a part of  a bonus subseries?)
     */
    private boolean inBonus;

    
    /** This is usesd when a player is first registered and a PlayerInfo object is first created */
    public void initSeries(TrialList trialList) throws IOException, IllegalInputException, ReflectiveOperationException, RuleParseException {

	if (allSeries.size()>0) throw new IllegalArgumentException("Attempt to initialize PlayerInfor.allSeries again");
	allSeries.clear();
	for( ParaSet para: trialList) {
	    allSeries.add(new Series(para));
	}
    }

    

    /** This method should be called after restoring the object from
      the SQL database, in order to re-create some of the necessary
      non-persistent structures. Typically, this may be needed if
      player resumes his activity after the Game Server has been
      restarted.  In particular, we restore the "series" structure,
      reloading paramter sets from the disk files and and putting
      episodes in their series arrays.

      <p>
      We also review the episodes, and "give up" all incomplete ones, because
      they don't have their transcripts and rules loaded, and cannot
      be continued. This may happen only rarely, when an episode
      had been persisted before beeing completed (thru cascading from
      the player being persisted), and then the server
      was restarted.     
    */
    public void restoreTransientFields() throws IOException, IllegalInputException, ReflectiveOperationException, RuleParseException {
	String exp = experimentPlan;
	// grandfathering older (pre 1.016) entries
	if (exp==null || exp.equals("")) {
	    exp = TrialList.extractExperimentPlanFromPlayerId(playerId);
	}
	    
	TrialList trialList  = new TrialList(exp, trialListId);
	allSeries.clear();

	int k = 0;

	System.err.println("Restore: will check " + allEpisodes.size() + " episodes");
	for(int j=0; j< trialList.size(); j++) {
	    ParaSet para =trialList.get(j);
	    String ruleSetName = para.getRuleSetName();
	    RuleSet rules = AllRuleSets.obtain(ruleSetName);

	    
	    Series ser = new Series(para);
	    allSeries.add(ser);
	    boolean needSave=false;
	    while(k<allEpisodes.size() && allEpisodes.get(k).seriesNo==j) {
		System.err.print("Restore: check series=" + j +", ae["+k+"]=");
		EpisodeInfo epi = allEpisodes.get(k++);
		System.err.println(epi.report()+", completed=" + epi.isCompleted());

		epi.setRules(rules); // just in case the GUI client needs them
		// for some display
		    
		
		if (!epi.isCompleted()) {
		    epi.giveUp();
		    // save the "givenUp" flag in the SQL database. No CSV files to write, though.
		    //Main.persistObjects(epi);
		    needSave=true;
		}
		ser.episodes.add(epi);
	    }
	    ser.gg.advance(ser.episodes.size());
	    if (needSave) saveMe();
	}

	
    }

    /** Retrieves the most recent episode, which may be completed or incomplete.
     */
    public EpisodeInfo mostRecentEpisode() {
	for(int k= Math.min(currentSeriesNo, allSeries.size()-1); k>=0; k--) {
	    Series ser=allSeries.get(k);
	    if (ser.episodes.size()>0) return ser.episodes.lastElement();
	}
	return null;
    }
    
    
    /** Returns the currently unfinished last episode to be resumed,
	or a new episode (in the current series or the next series, as
	the case may be), or null if this player has finished with all
	series. This is used by the /GameService2/newEpisode web API call. */
    public synchronized EpisodeInfo episodeToDo() throws IOException, RuleParseException {

	Logging.info("episodeToDo(pid="+playerId+"); cs=" + currentSeriesNo +", finished=" + alreadyFinished());

	boolean needSave=false;
	try {
	while(currentSeriesNo < allSeries.size()) {	    
	    Series ser=getCurrentSeries();
	    if (ser!=null && ser.episodes.size()>0) {

		if (inBonus && ser.bonusHasBeenEarned()) {
		    goToNextSeries();
		    continue;
		}
		
		EpisodeInfo epi = ser.episodes.lastElement();
		// should we resume the last episode?
		if (!epi.isCompleted()) {
		    if (epi.isNotPlayable()) {
			epi.giveUp();			
			// we just do SQL persist but don't bother saving CSV, since the data
			// probably just aren't there anyway
			needSave=true;
			//Main.persistObjects(x);
		    } else {
			Logging.info("episodeToDo(pid="+playerId+"): returning existing episode " + epi.episodeId);
			return epi;
		    }
		}
	    }
	    
	    EpisodeInfo epi = null;
	    if (canHaveAnotherRegularEpisode()) {
		epi = EpisodeInfo.mkEpisodeInfo(currentSeriesNo, ser.gg, ser.para, false);
	    } else if (canHaveAnotherBonusEpisode()) {
		epi = EpisodeInfo.mkEpisodeInfo(currentSeriesNo, ser.gg, ser.para, true);
	    }

	    if (epi!=null) {
		// The error-free sstretch continues across episode border
		if (ser.episodes.size()>0) {
		    epi.setLastStretch(ser.episodes.lastElement().getLastStretch());
		}
		
		ser.episodes.add(epi);
		addEpisode(epi);	
		Logging.info("episodeToDo(pid="+playerId+"): returning new episode " + epi.episodeId);
		return epi;
	    }
	    Logging.info("episodeToDo(pid="+playerId+"): nextSeries");
	    goToNextSeries();
	}
	} finally {
	    if (needSave) saveMe();
	}
	
	Logging.info("episodeToDo(pid="+playerId+"): cannot return anything");

	return null;
    }

    private Series whoseEpisode(EpisodeInfo epi) {
	Series s = allSeries.get( epi.seriesNo);
	
	for(EpisodeInfo e: s.episodes) {
	    if (e==epi) return s;
	}
	// This could indicate some problem with the way we use JPA
	System.err.println("whoseEpisode: detected an episod not stored in the current series structure : " + epi);
	return null;
    }

    /** Gives a link to the ParaSet associated with a given episode */
    public ParaSet getPara(EpisodeInfo epi) {
	return  whoseEpisode(epi).para;
    }


    private String completionCode;
    /** The completion code, a string that the player can report as a proof of 
	his completion of the experiment plan. It is set when the current series
	number is incremented beyond the last parameter set number.
     */
    public String getCompletionCode() { return completionCode; }
    public void setCompletionCode(String _completionCode) { completionCode = _completionCode; }

    /** Creates a more or less unique string ID for this Episode object */
    private String buildCompletionCode() {
	String s = playerId + "-" + Episode.sdf.format(new Date()) + "-";
	for(int i=0; i<4; i++) {
	    int k =  Episode.random.nextInt(10 + 'F'-'A'+1);
	    char c = (k<10) ? (char)('0' + k) : (char)('A' + k-10);
	    s += c;
	}
	return s;
    } 
    
    /** Adjusts the counters/flags indicating what series and
	subseries we are on, and persists this object. This is the
	only place in the code where the current series number can be
	incremented. If the series number reaches the last possible
	value (the one beyond the range of parameter set numbers, the
	completion code is set.
     */
    synchronized private void goToNextSeries() {
	if (alreadyFinished()) return;
	currentSeriesNo++;
	inBonus=false;

	if (alreadyFinished() && completionCode ==null) {
	    completionCode = buildCompletionCode();
	} 
	//Main.persistObjects(this);
	saveMe();
    }

    private int totalRewardEarned;
    public int getTotalRewardEarned() {
	//System.err.println("getTotalReward("+playerId+")=" + totalRewardEarned);
	return totalRewardEarned;
    }
    public void setTotalRewardEarned(int _totalRewardEarned) { totalRewardEarned = _totalRewardEarned; }

    /** Recomputes this player's totalRewardEarned, based on all episodes in his record*/
    private void updateTotalReward() {
	int sum=0;
	int cnt=0;
	for(Series ser: allSeries) {
	    int s=0, f=1;
	    for(EpisodeInfo epi: ser.episodes) {
		cnt++;
		s +=  epi.getTotalRewardEarned();  
		f = Math.max(f, epi.getXFactor());
	    }
	    sum += s;
	}
	totalRewardEarned=sum;
	System.err.println("Total reward("+playerId+"):=" + totalRewardEarned +", based on " + cnt + " episodes");
    }


    /** @return { {s0,f0}, {s1,f1}, {s2,f2}....}, which are the
	per-series components that sum to reward=s0*f0+ s1*f1+ s2*f2 +....
     */
    int[][] getRewardsAndFactorsPerSeries() {
	int n = Math.min( currentSeriesNo+1, allSeries.size());
	int[][] rx = new int[n][];

	for(int j=0; j< n; j++) {
	    Series ser = allSeries.get(j);
	    int s=0, f=1;
	    for(EpisodeInfo epi: ser.episodes) {
		s +=  epi.getTotalRewardEarned();
		f = Math.max(f, epi.getXFactor());
	    }
	    rx[j++] = new int[]{s, f};
	}
	return rx;
    }

    

    
    /** This method is called after an episode completes. It computes
	the applicable rewards (if the board has been cleared or
	(since 4.007) stalemared, calls the SQL persist operations,
	writes CSV files, and, if needed, switches the series and
	subseries.

	@param epi An episode that's just completed; so all data are in memory 
	now.
    */
    void ended(EpisodeInfo epi) throws IOException {
	Series ser = whoseEpisode(epi);
	if (ser==null) throw new IllegalArgumentException("Could not figure to which series this episode belongs");
	epi.endTime=new Date();
	
	if (epi.stalemate && !epi.stalematesAsClears) {	    
	    // The experimenters should try to design rule sets so that stalemates
	    // do not happen; but if one does, let just finish this series
	    // to avoid extra annoyance for the player
	    goToNextSeries();
	} else if (epi.cleared || epi.earlyWin ||
		   epi.stalemate && epi.stalematesAsClears) {
	    //double smax = ser.para.getDouble("max_points");
	    //double smin = ser.para.getDouble("min_points");
	    //double b = ser.para.getDouble("b");

	    //double d = epi.attemptSpent - epi.getNPiecesStart();
	    // For completions, nPiecesStart==doneMoveCnt, but for
	    // stalemates, we must use the latter
	    double d = epi.attemptSpent - epi.doneMoveCnt;
	    
	    epi.rewardMain =  ser.para.kantorLupyanReward(d);
	    //(int)Math.round( smin + (smax-smin)/(1.0 + Math.exp(b*(d-2))));
	    if (epi.bonus) {
		ser.assignBonus();
	    }
	    updateTotalReward();
	}

	epi.updateFinishCode();
	Logging.info("PlayerInfo.ended(epi=" + epi.getEpisodeId()+"); finishCode =" + epi.finishCode);
	// save the data in the SQL server
	saveMe();
	// save the data in the CSV files
	File f =  Files.boardsFile(playerId);
	Board b = epi.getCurrentBoard(true);
	BoardManager.saveToFile(b, playerId, epi.episodeId, f);
	f =  Files.transcriptsFile(playerId);
	TranscriptManager.saveTranscriptToFile(playerId, epi.episodeId, f, epi.transcript);
	f =  Files.detailedTranscriptsFile(playerId);
	epi.saveDetailedTranscriptToFile(f);
	
    }

    /** Generates a concise report on this player's history, handy for
	debugging. It gives summaries of all episodes done (or in
	progress) by this player, broken down by series. */
    public String report() {
	Vector<String> v = new Vector<>();
	int j=0;
	for(Series ser: allSeries) {
	    String s="";
	    if (j==currentSeriesNo) s+= (inBonus? "*B*" : "*M*");
	    s += "[S"+j+"]";
	    for(EpisodeInfo epi: ser.episodes) s += epi.report();
	    v.add(s);
	    j++;
	}
	v.add("id="+id+", curSer="+currentSeriesNo+" b="+inBonus+", R=$"+getTotalRewardEarned());
	return String.join("\n", v);
    }


    /** Where can we go from here? */
    public static enum Transition {
	/** A main-subseries episode in the same series*/
	MAIN,
	/** A bonus episode in the same series */
	BONUS,
	/** Start next series (that is, a new param set, with new rules) */
	NEXT,
	/** End the interaction with the system, as the player has at
	    least sampled all param sets, and has completed (or given up
	    on) all of them */	   
	END};
    /** What type of action takes the player to a particular destination? */
    public static enum Action {
	/** Default transition -- no special choice */
	DEFAULT,
	/** Activate bonus */
	ACTIVATE,
	/** Give up */
	GIVE_UP};

    public class TransitionMap extends HashMap<Transition,Action> {
	/** After an episode has been completed, what other episode(s) can follow?
	    This object is transmitted to the client as JSON, and can be used 
	    to draw all appropriate transition buttons.
	    <p>
	    Note that the map may be empty if no more episodes can be played. 
	*/
	public TransitionMap() {
	    
	    Series ser = getCurrentSeries();
	    if (ser==null) return;
	    boolean isLastSeries = (currentSeriesNo + 1 == allSeries.size());

	    // Where do you really get if you are done with this series?
	    Transition whitherNext = isLastSeries?Transition.END: Transition.NEXT;
	    
	    if (inBonus) {
		if (canHaveAnotherBonusEpisode()) {
		    put(Transition.BONUS, Action.DEFAULT);
		    put(whitherNext, Action.GIVE_UP);
		} else {
		    put(whitherNext, Action.DEFAULT);
		}
	    } else {

		if (canHaveAnotherRegularEpisode()) {
		    // space left to continue or give up
		    put(Transition.MAIN, Action.DEFAULT);
		    put(whitherNext, Action.GIVE_UP);
		} else {
		    // end of series
		    put(whitherNext, Action.DEFAULT);
		}

		if (canActivateBonus())  put(Transition.BONUS, Action.ACTIVATE);
	    }
	}
    }

    /** Saves this object (and the associated Episode objects, via
	cascading) data in the SQL database. The assumption is that 
	this object is detached, so we call a method which will
	create a new EM and merge this object to the new persistence context.
     */
    public void saveMe() {
	Main.saveObject(this);
    }

    /** Computes the "faces" vector for the series to which the 
	specified episode belongs. This is used by Kevin's GUI tool
	in the DOUBLING incentive scheme display (ver 4.006)
	
	Ignoring successful picks to keep players from gaming the system.

	@param epi We pass it in so that everything will work
	correctly even if this is part of a /move call that ended
	the last episode of the series, and currentSeriesNo may already be
	referring to the next series.
    */
    Vector<Boolean> computeFaces(EpisodeInfo epi)// throws IOException
    {
	Series ser = whoseEpisode(epi);
	Vector<Boolean> v = new Vector<>();
	if (ser==null) return v;
	for(Episode e: ser.episodes) {
	    Vector<Episode.Pick> t=e.transcript;
	    if (t==null) continue;
	    for(Episode.Pick pick: t) {
		if (pick instanceof Episode.Move ||
		    pick.code!=Episode.CODE.ACCEPT)
		v.add( pick.code==Episode.CODE.ACCEPT);
	    }
	}
	return v;
    }

}
 
