package org.chernovia.net.games.parlour.acro.server;

import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Predicate;

import org.chernovia.lib.misc.MiscUtil;
import org.chernovia.lib.netgames.roomserv.Connection;
import org.chernovia.lib.netgames.roomserv.NetServ;

public class AcroGame implements Runnable {
	static final String
		ACRO_DG = "&",
		DG_DELIM = "~",
		NO_TOPIC = "",
		NO_ACRO = "";

	static final int
		DG_NEW_ACRO = 0,
		DG_ACRO_TIME = 1,
		DG_ACRO_ENTERED = 2,
		DG_SHOW_ACROS = 3,
		DG_VOTE_TIME = 4,
		DG_RESULTS = 5,
		DG_WAIT_TIME = 6,
		DG_PLAYERS = 7,
		DG_INFO = 8,
		DG_VARS = 9,
		DG_NEXT_ACRO = 10;
	
	static final int
		MOD_RESET = -3,
		MOD_PAUSE = -2,
		MOD_IDLE = -1,
		MOD_NEW = 0,
		MOD_ACRO = 1,
		MOD_VOTE = 2,
		MOD_WAIT = 3;

	static class AcroList {
		String sentence;
		final int author;
		int votes = 0;
		long time;
		public AcroList(String sentence, int author, long time) {
			this.sentence = sentence;
			this.author = author;
			this.time = System.currentTimeMillis() - time;
		}
	}
	
	private AcroPlayer players[];
	private AcroList acrolist[];
	private AcroLetter[] letters;
	private String[] topics;
	private String acro,topic,winshout,newshout; //,deftopic,adshout;
	private NetServ serv;
	private Connection manager;
	private long newtime;
	private int chan, mode,
	acrotime,votetime,waittime,basetime,
	atimevar,vtimevar,acronum,numplay,maxacro,minacro,
	speedpts,lastwin,round,winscore,longhand,acrolen,
	maxcol, votecol, maxtopic, maxround, maxplay;
	boolean FLATTIME,REVEAL,TIEBONUS,TOPICS,GUI,ADULT,SHOUTING;
	boolean TESTING = false;
	AcroBox box;
	
	public String dumpGame() {
		StringBuilder dump =
		new StringBuilder(
		AcroGame.ACRO_DG + DG_DELIM + AcroGame.DG_INFO + DG_DELIM);
		dump.append(chan + DG_DELIM);
		dump.append(((manager == null) ? "-" : manager) + DG_DELIM);
		dump.append(round + DG_DELIM);
		dump.append(((topic == NO_TOPIC) ? "-" : topic) + DG_DELIM);
		dump.append(((acro == NO_ACRO) ? "-" : acro) + DG_DELIM);
		dump.append(mode + DG_DELIM);
		return dump.toString();
	}
	
	public String dumpVars() {
		StringBuilder dump = new StringBuilder(
		ACRO_DG + DG_DELIM + DG_VARS + DG_DELIM);
		dump.append(DG_DELIM);
		dump.append(TIEBONUS ? 
		"fastest acro breaks tie" : "ties are not broken");	
		dump.append(DG_DELIM);
		dump.append(REVEAL ? "public voting" : "private voting");
		dump.append(DG_DELIM);
		dump.append(TOPICS ? "topics" : "no topics");
		dump.append(DG_DELIM);
		dump.append(FLATTIME ? 
		"dynamic acro times" : "static acro times");
		dump.append(DG_DELIM);
		dump.append(ADULT ? "adult themes" : "clean themes");
		dump.append(DG_DELIM);
		dump.append("Current players: " + countPlayers());
		dump.append(DG_DELIM);
		dump.append("Max players: " + maxplay);
		dump.append(DG_DELIM);
		dump.append("Min letters: " + minacro);
		dump.append(DG_DELIM);
		dump.append("Max letters: " + maxacro);
		return dump.toString();
	}
	
	public String dumpPlayers() {
		StringBuilder dump =
			new StringBuilder(AcroGame.ACRO_DG + DG_DELIM +
			AcroGame.DG_PLAYERS + DG_DELIM);
		StringBuffer playStr = new StringBuffer();
		int n=0;
		for (Connection conn : serv.getAllConnections()) {
			if (conn.getChan() == chan) {
				n++;
				playStr.append(conn.getHandle() + DG_DELIM);
				int p = getPlayer(conn.getHandle());
				if (p >= 0) playStr.append(players[p].score + DG_DELIM);
				else playStr.append("-" + DG_DELIM);
			}
		}
		dump.append(n + DG_DELIM + playStr);
		return dump.toString();
	}	
	
	public String dumpAcros() {
		StringBuilder dump = new StringBuilder(ACRO_DG + DG_DELIM +
		((mode == MOD_WAIT) ?	DG_RESULTS: DG_SHOW_ACROS) + DG_DELIM);
		dump.append(acronum + DG_DELIM);
		for (int i=0;i<acronum;i++) {
			dump.append(acrolist[i].sentence + DG_DELIM +
			getPlayer(acrolist[i].author).getName() + DG_DELIM + 
			acrolist[i].votes + DG_DELIM);
		}
		return dump.toString();
	}
	
	public void dumpAll(Connection conn) {
		conn.tell(dumpGame());
		conn.tell(dumpVars());
		conn.tell(dumpPlayers());
		//if (mode > MOD_ACRO) conn.tell(dumpAcros());
	}
	private void spamAllGUI() {
		spam(dumpGame(),true);
		spam(dumpVars(),true);
		spam(dumpPlayers(),true);
	}
	private void spam(String s) { serv.tch(chan,s,false,false); }
	private void spam(String s, boolean dg) {
		serv.tch(chan,s,false,dg);
	}

	private long countPlayers() {
		Predicate<Connection> predicate = (conn -> conn.getChan() == chan);
		return serv.getAllConnections().stream().filter(predicate).count();
	}	

	public AcroGame(NetServ srv, int c) {
		manager = null;	serv = srv; chan = c;
		//TODO: make this only for Twitch
		box = new AcroBox(); box.setVisible(true); 
	}

	private void initGame() {
		mode = MOD_IDLE; newLetters(AcroServ.ABCDEF);
		if (TESTING) {
			acrotime = 20; votetime = 10; waittime = 5;
		}
		else {
			acrotime = 90; votetime = 60; waittime = 30;
			//acrotime = 30; votetime = 30; waittime = 5;
		}
		atimevar = 6; vtimevar = 6; basetime = 20;
		winscore = 30; speedpts = 2;
		maxacro = 8; minacro = 3;
		maxcol = 60; votecol = 10;
		maxround = 99; maxtopic = 3;
		maxplay = 24; //TODO: too many?!
		FLATTIME = true; TOPICS = true; SHOUTING = false;
		REVEAL = false; TIEBONUS = false; GUI = false; ADULT = false;
		winshout = " is really KEWL!";
		newshout = "New Acro Game starting in channel " +
		chan + "!";
		//adshout = "Play Acrophobia!  Finger me for details.";
		spam("Version "+AcroServ.VERSION+". Whee.");
	}

	private boolean initNewGame() {
		acro = NO_ACRO; newtime = 0; lastwin = -1;
		acrolen = newLength();
		round = 0; numplay = 0; topic = NO_TOPIC;
		players = new AcroPlayer[maxplay];
		topics = new String[maxtopic];
		return true;
	}

	public void stop() {
		Thread.currentThread().interrupt();
	}

	@Override
	public void run() {
		try {
			initGame();
			while (initNewGame()) {
				if (mode != MOD_IDLE) mode = MOD_ACRO;
				else idle();
				spam("New Game Starting!"); int deserted = 0;
				if (SHOUTING) serv.broadcast(newshout);
				while (mode > MOD_NEW) {
					box.updateHiScores(new StringTokenizer( //just for the colors
					AcroBase.topTen("wins"),AcroServ.CR));
					acrolist = new AcroList[maxplay];
					acronum = 0; round++;
					acroRound(); if (GUI) spamAllGUI();
					acrolen = newLength();
					if (acronum == 0) {
						spam("No acros."); deserted++;
						if (deserted == 3) {
							spam("Bah. noone's here. I sleep.");
							mode = MOD_IDLE;
						}
					}
					else if (!TESTING && acronum < 3) {
						deserted = 0;
						spam(showAcros());
						spam("Too few acros to vote on (need at least three).");
					}
					else {
						deserted = 0; 
						voteRound(); if (GUI) spamAllGUI();
						scoreRound(); if (GUI) spamAllGUI();
					}
				}
			}
		}
		catch (Exception augh) {
			spam("Oops: " + augh.getMessage());
			augh.printStackTrace();
		}
	}

	private void idle() {
		try {
			while (mode != MOD_ACRO)
				Thread.sleep(999999); }
		catch (InterruptedException e) {
			if (mode != MOD_ACRO)
				idle();
		}
	}

	private String makeAcro(int numlets) {
		int t=0;
		for (int x=0;x<26;x++) t += letters[x].prob;
		StringBuilder result = new StringBuilder("");
		int c=0;
		for (int x=0;x<numlets;x++) {
			int r = MiscUtil.randInt(t); int z=0;
			for (c=0;c<26;c++) {
				z += letters[c].prob;
				if (z>r) break;
			}
			result.append(letters[c].c.toUpperCase());
		}
		return result.toString();
	}

	private void acroRound() {
		if (mode > MOD_IDLE)
			mode = MOD_ACRO;
		else
			return;
		if (topic != NO_TOPIC)
			spam("Topic: " + topic);
		acro = makeAcro(acrolen); box.updateAcro(acro, topic);
		if (GUI)
			spam(ACRO_DG + DG_DELIM + DG_NEW_ACRO + DG_DELIM + acro + DG_DELIM + round,true);
		int t = makeAcroTime();
		spam("Round " + round + " Acro: " + acro + AcroServ.CR + 
		"You have " + t + " seconds.");
		if (GUI)
			spam(ACRO_DG + DG_DELIM + DG_ACRO_TIME + DG_DELIM + t + DG_DELIM,true);
		newtime = System.currentTimeMillis();
		sleeper((t/2)*1000);
		spam(t/2 + " seconds remaining.");
		sleeper(((t/2)-(t/6))*1000);
		spam(t/6 + " seconds...");
		sleeper((t/6)*1000);
	}

	private void sleeper(long t) {
		long rt = sleeping(t);
		while (rt > 0) {
			spam("Unpaused. (" + rt/1000 + " seconds remaining)");
			rt = sleeping(rt);
		}
	}

	private long sleeping(long duration) {
		int oldmode = mode;
		if (mode < MOD_NEW)
			return 0; //skip past stuff
		long t = System.currentTimeMillis();
		try {
			Thread.sleep(duration);
		} catch (InterruptedException e) {
			if (mode != MOD_PAUSE) {
				spam("Skipping...");
				return 0;
			}
			spam("Pausing...");
			try {
				Thread.sleep(999999);
			} catch (InterruptedException i) {
				mode = oldmode;
			}
			return duration - (System.currentTimeMillis() - t);
		}
		return 0;
	}

	private int makeAcroTime() {
		if (FLATTIME) return acrotime;
		return basetime + (atimevar*acro.length());
	}

	private int getVoteTime() {
		if (FLATTIME) return votetime;
		return basetime + (vtimevar*acronum);
	}

	private String showAcros() {
		StringBuilder result = new StringBuilder("Round " + round + " Acros: " + AcroServ.CR);
		jumble();
		//S.append("____________________________________________" + AcroServ.CR);
		longhand = 0; // for formatting
		for (int x=0;x<acronum;x++) {
			result.append((x+1) + ". ");
			result.append(acrolist[x].sentence + AcroServ.CR);
			int l = players[acrolist[x].author].getName().length();
			if (l > longhand) longhand = l; //get longest handle
		}
		//S.append("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~" + AcroServ.CR);
		return result.toString();
	}

	private void jumble() {
		if (acronum < 2) return;
		AcroList tmpacro;
		for (int x=0;x<acronum;x++) {
			int y = MiscUtil.randInt(acronum);
			tmpacro = acrolist[y];
			acrolist[y] = acrolist[x];
			acrolist[x] = tmpacro;
		}
	}

	private void voteRound() {
		if (mode > MOD_IDLE)
			mode = MOD_VOTE;
		else
			return;
		if (topic != NO_TOPIC)
			spam("Topic: " + topic);
		spam(showAcros());
		if (GUI)
			spam(dumpAcros(),true);
		box.updateAcros(acronum,acrolist); 
		int t=getVoteTime(); 
		spam("Time to vote!  Enter the number of an acro above. " +
		AcroServ.CR + "You have " + t + " seconds.");
		sleeper((t-(t/6))*1000); spam(t/6 + " seconds...");
		sleeper ((t/6)*1000);
	}

	private void scoreRound() {
		if (mode > MOD_IDLE)
			mode = MOD_WAIT;
		else
			return;
		if (topic != NO_TOPIC)
			spam("Topic: " + topic);
		spam(showVote(false));
		spam(showScore());
		box.updateScores(numplay,players);
		int w = winCheck(); //System.out.println(w);
		if (w<0) waitRound();
		else  {
			spam(players[w].getName() + " wins!" + AcroServ.CR);
			if (SHOUTING) serv.broadcast(
				players[w].getName() + winshout);
			spam(summarize());
			//showHistory();
			AcroBase.updateStats(this, players[w]);
			spam("New game in " + (2*waittime) + " seconds.");
			sleeper(waittime*2000);
			mode = MOD_NEW;
		}
	}

	private void waitRound() {
		mode = MOD_WAIT; topic = NO_TOPIC; getTopics();
		if (TOPICS && lastwin >= 0) {
			spam(players[lastwin].getName() +
					" may choose a topic: " +
					AcroServ.CR + showTopics());
			serv.tell(players[lastwin].getName(),
					"Next acro: " + acrolen + " letters",false,false);
		}
		spam("Next round in " + waittime + " seconds.");
		sleeper(waittime*1000); lastwin = -1;
	}

	private void getTopics() {
		List<String> topicList = AcroBase.readFile(AcroServ.TOPFILE);
		int lof = topicList.size();
		int[] topicKey = new int[maxtopic];
		for (int x=0;x<maxtopic;x++) {
			boolean match;
			do {
				match = false;
				topicKey[x] = MiscUtil.randInt(lof-1);
				//System.out.println("Topic #" + x + ": " +
				//toplist[x]);
				for (int y=x-1;y>=0;y--)
					if (topicKey[x] == topicKey[y]) match = true;
			} while (match);
		}
		for (int t=0;t<maxtopic;t++)
			topics[t] = topicList.get(topicKey[t]);
		//topics[MAXTOPIC-1] = DEFTOPIC;
	}

	private String showTopics() {
		StringBuilder result = new StringBuilder();
		for (int t=0;t<maxtopic;t++)
			result.append((t+1) + ". " + topics[t] + AcroServ.CR);
		return result.toString();
	}

	protected void newTopic(int p, String msg) {
		String n = players[p].getName();
		if (msg.length()>6 && msg.startsWith("topic ")) {
			topic = msg.substring(6);
			spam(n + " selects " + topic + "...");
		}
		else {
			int t = MiscUtil.strToInt(msg)-1;
			if (t < 0 || t >= maxtopic) {
				serv.tell(n,"Bad Topic.",false,false);
			}
			else {
				topic = topics[t];
				spam(n + " selects " + topic + "...");
			}
		}
	}

	private int winCheck() {
		int w=0;
		for (int x=0;x<numplay;x++) {
			if (players[x].score > w) w = players[x].score;
		}
		if (w < winscore) return -1;
		int z=0; int p=0;
		for (int x=0;x<numplay;x++) {
			if (players[x].score == w)  { p=x; z++; }
		}
		if (z == 1)
			return p;
		else
			return -2; //tie!
	}

	private String showVote(boolean fancyformat) {
		String CR = AcroServ.CR;
		StringBuilder result = new StringBuilder(CR + "Round " +
				round + " Voting Results: " + CR + CR);
		int acrocol = maxcol - (votecol + longhand);
		if (fancyformat) for (int x=0;x<acronum;x++) {
			int h =
				players[acrolist[x].author].getName().length();
			String acroline =
				players[acrolist[x].author].getName() +
				MiscUtil.txtPad(longhand-h) +
				acrolist[x].votes + " votes! (" +
				acrolist[x].time/(float)1000 + ")" + CR;
			int l = acrolist[x].sentence.length();
			if (l < acrocol) {
				result.append("  " + acrolist[x].sentence +
						MiscUtil.txtPad(acrocol - l) + acroline);
			}
			else {
				int i = MiscUtil.lastSpc(
						acrolist[x].sentence.substring(0,acrocol));
				result.append("  " + acrolist[x].sentence.substring(0,i) +
						MiscUtil.txtPad(acrocol - i) +
						acroline + " " + acrolist[x].sentence.substring(i) +
						CR);
			}
		}
		else for (int x=0;x<acronum;x++) {
			result.append(
			acrolist[x].sentence + " (" + players[acrolist[x].author].getName() + ") " +
			acrolist[x].votes + " votes! (" + acrolist[x].time/(float)1000 + ")" + CR);
		}
		result.append(CR);
		for (int x=0;x<acronum;x++) {
			int a = acrolist[x].author;
			if (acrolist[x].votes>0 && players[a].vote>=0)
				players[a].score += acrolist[x].votes;
		}
		int s = getSpeed();
		if (s>=0) {
			result.append(" " + players[s].getName() + " -> " + speedpts + " speed bonus points");
			players[s].score += speedpts;
			result.append(CR);
		}
		result.append(winners() + CR);
		return result.toString();
	}

	private int getSpeed() {
		int s = -1;
		long t = 9999999;
		for (int x=0;x<acronum;x++) {
			int a = acrolist[x].author;
			if (acrolist[x].time < t &&
					players[a].score < winscore-4 &&
					players[a].vote >= 0 &&
					acrolist[x].votes > 0) {
				s = a;
				t = acrolist[x].time;
			}
		}
		return s;
	}

	private String winners() {
		String CR = AcroServ.CR;
		StringBuilder result = new StringBuilder("");
		for (int x=0;x<acronum;x++)
			if (players[acrolist[x].author].vote < 0) {
				result.append(" " + players[acrolist[x].author].getName() +
						" did not vote, and thus forfeits this round." + CR);
			}
		int w = 0, l = acro.length();
		long wintime = 999999;
		for (int x=0;x<acronum;x++)
			if (players[acrolist[x].author].vote >= 0 &&
					acrolist[x].votes > w)  {
				w = acrolist[x].votes;
			}
		for (int x=0;x<acronum;x++)
			if (acrolist[x].votes == w && w > 0 &&
					players[acrolist[x].author].vote >= 0) {
				if (TIEBONUS) {
					players[acrolist[x].author].score += l;
					result.append(" " +
							players[acrolist[x].author].getName());
				}
				if (acrolist[x].time < wintime) {
					lastwin = acrolist[x].author;
					wintime = acrolist[x].time;
				}
			}
		if (w < 1)
			result.append(" No winners.");
		else if (TIEBONUS) {
			result.append(" -> " + l + " bonus points!");
		}
		else if (lastwin >= 0) { //can lastwin be < 0?
			result.append(" " + players[lastwin].getName() +
					" -> " + l + " bonus points!");
			players[lastwin].score += l;
		}
		if (TOPICS && lastwin >= 0)
			result.append(CR + " " + players[lastwin].getName() +
			" gets to choose the next topic.");
		return result.toString();
	}

	private String showScore() {
		// record scores
		for (int p=0;p<numplay;p++) {
			int x = findAcro(p); int v = players[p].vote;
			if (x<0) { //did not enter an acro this round
				if (v<0)
					players[p].save("",-1,0);
				else
					players[p].save("",acrolist[v].author,0);
			}
			else {
				if (v<0)
					players[p].save(acrolist[x].sentence,-1,acrolist[x].votes);
				else
					players[p].save(acrolist[x].sentence,acrolist[v].author,acrolist[x].votes);
			}
		}
		// show scores
		StringBuilder result = new StringBuilder(" Round " + round + " Scores: " + AcroServ.CR);
		for (int x=0;x<numplay;x++) {
			if (players[x].score>0)
				result.append(" " + players[x].getName() + ": " + players[x].score + " ");
			players[x].vote = -1;
		}
		return result.toString() + AcroServ.CR;
	}
	
	private String summarize()  {
		StringBuilder result = new StringBuilder("History:" + AcroServ.CR);
		if (serv.getType() != NetServ.IRC)
			for (int p=0;p<numplay;p++)	{
				result.append(showVoteHistory(p));
			}
		return result.toString();
	}

	private String showVoteHistory(int p) {
		StringBuilder result = new StringBuilder(" " + players[p].getName() + ": ");
		for (int x=0;x<numplay;x++) {
			int c = 0;
			for (int r=0;r<players[x].acros;r++) { //erp?
				if (players[x].record[r].vote == p) c++;
			}
			if (c > 0)
				result.append(players[x].getName() +	"(" + c + ") ");
		}
		result.append(AcroServ.CR);
		return result.toString();
	}

	private void showHistory() {
		for (int p=0;p<numplay;p++) {
			serv.tell(players[p].getName(),
			"Game History: (" +	players[p].sumacros() +	" acros)",
			false,false);
			for (int r=0;r<players[p].acros;r++)
				if (!players[p].record[r].acro.equals("")) {
					serv.tell(players[p].getName(),
						players[p].record[r].acro + " (" +
						players[p].record[r].votes + " votes)",false,false);
				}
		}
	}

	protected boolean isLegal(String A) {
		StringTokenizer tokenizer = new StringTokenizer(A);
		System.out.println("Received acro: " + A + " (current: " + acro + ")");
		if (acro.length() != tokenizer.countTokens()) {
			System.out.println("Bad length: " + tokenizer.countTokens()); return false;
		}
		int x=0; 
		while (tokenizer.hasMoreTokens()) {
			String token = tokenizer.nextToken();
			char ch = token.charAt(0);
			if (ch == '"' && token.length()>1)
				ch = token.charAt(1);
			if (acro.charAt(x)!=ch) {
				System.out.println("Bad match: " + ch + " != " + acro.charAt(x));
				return false; 
			}
			x++;
		}
		return true;
	}

	protected String getAcronym(String sentence) {
		StringBuilder result = new StringBuilder();
		StringTokenizer tokenizer = new StringTokenizer(sentence);
		while (tokenizer.hasMoreTokens())
			result.append(tokenizer.nextToken().charAt(0));
		return result.toString().toUpperCase();
	}

	private int findAcro(int p)  { //find acro by player
		for (int x=0;x<acronum;x++)
			if (acrolist[x].author == p) return x;
		return -1;
	}

	private int newLength() {
		return MiscUtil.randInt(maxacro-minacro) + minacro;
	}

	protected void newLetters(String ABCFILE) {
		letters =
			AcroLetter.loadABC(ABCFILE + AcroLetter.LETTEXT);
		if (letters == null) {
			spam("Can't find Letter File: " + ABCFILE);
			spam("Using default (" + AcroServ.ABCDEF + ") " +
			"instead.");
			letters = AcroLetter.loadABC(AcroServ.ABCDEF +
					AcroLetter.LETTEXT);
		}
		else spam("Loaded Letter File: " + ABCFILE);
	}

	private int addNewPlayer(Connection conn) {
		String handle = conn.getHandle();
		if (numplay >= maxplay - 1)  {
			serv.tell(handle,"Game Full!?",false,false);
			return -1;
		}
		players[numplay] = new AcroPlayer(this,conn);
		numplay++; serv.tell(handle,"Welcome!",false,false);
		box.updateScores(numplay, players);
		return numplay - 1;
	}

	//public methods

	protected void newAcro(Connection conn, String A) { //throws Exception {
		String handle = conn.getHandle();
		int p = getPlayer(handle);
		if (p < 0) {
			p = addNewPlayer(conn);	if (p < 0) return;
		}
		int x = findAcro(p);
		if (x>=0) {
			acrolist[x].sentence = A;
			acrolist[x].time=System.currentTimeMillis()-newtime;
			serv.tell(handle,"Changed your acro.",false,false);
			serv.tell(handle,"Time: " +
			acrolist[x].time / (float)1000,false,false);
		}
		else {
			acrolist[acronum++] = new AcroList(A,p,newtime);
			serv.tell(handle,"Entered your acro. Time: " +
			acrolist[acronum-1].time / (float)1000,false,false);
			spam("Acro #" + acronum + " received!");
		}
	}

	protected void newVote(Connection conn, int v) {
		String handle = conn.getHandle();
		int p = getPlayer(handle);
		if (p < 0) {
			p = addNewPlayer(conn);	if (p < 0) return;
		}
		if (acrolist[v].author == p) {
			spam("Voting for oneself is not allowed, " +
					handle + "."); return;
		}
		if (players[p].vote>=0) //fix already voted for acro
			acrolist[players[p].vote].votes--;
		acrolist[v].votes++; players[p].vote = v;
		serv.tell(handle,"Your vote has been entered.",false,false);
	}

	protected int getPlayer(String name)  {
		for (int x=0;x<numplay;x++)
			if (name.equals(players[x].getName())) {
				return x;
			}
		return -1;
	}

	protected AcroPlayer getAcroPlayer(String name)  {
		for (int x=0;x<numplay;x++)
			if (name.equals(players[x].getName())) {
				return players[x];
			}
		return null;
	}

	protected String showLetters() {
		if (letters == null) return "Nothing loaded.";
		StringBuilder result = new StringBuilder();
		for (int x=0;x<26;x++) {
			result.append(letters[x].c + ": " +letters[x].prob + AcroServ.CR);
		}
		return result.toString();
	}
	
	Connection getManager() { return manager; }
	void setManager(Connection mgr) { manager = mgr; }
	int getMode() { return mode; }
	void setMode(int m) { mode = m; }
	int getChan() { return chan; }
	int getNumPlay() { return numplay; }
	int getNumAcros() { return acronum; }
	int getAcroTime() { return acrotime; }
	int getLastWin() { return lastwin; }
	int getMaxRound() { return maxround; }
	AcroPlayer getPlayer(int p) { return players[p]; }
	NetServ getServ() { return serv; }
	String getAcro() { return acro; }
	
	protected String listPlayers() {
		StringBuilder playstr = new StringBuilder("Players: " + AcroServ.CR);
		for (Connection conn : serv.getAllConnections()) {
			if (conn.getChan() == chan) {
				playstr.append(conn.getHandle() + ": ");
				int p = getPlayer(conn.getHandle());
				if (p >= 0) playstr.append(players[p].score);
				else playstr.append("-");
				playstr.append(AcroServ.CR);
			}
		}
		return playstr.toString();
	}
	
	public String listVars() {
		StringBuilder result = new StringBuilder();
		//result.append("Shouting: " + SHOUTING + AcroServ.CR);
		result.append("Settings: " + AcroServ.CR);
		result.append("Channel: " + chan);
		result.append(AcroServ.CR);
		result.append("Manager: " + (manager == null ? "Nobody" : manager.getHandle()));
		result.append(AcroServ.CR);
		result.append(TIEBONUS ? "fastest acro breaks tie" : "ties are not broken");
		result.append(AcroServ.CR);
		result.append(REVEAL ? "public voting" : "private voting");
		result.append(AcroServ.CR);
		result.append(TOPICS ? "topics" : "no topics");
		result.append(AcroServ.CR);
		result.append(FLATTIME ? "dynamic acro times" : "static acro times");
		result.append(AcroServ.CR);
		result.append(ADULT ? "adult themes" : "clean themes");
		result.append(AcroServ.CR);
		result.append("Current players: " + countPlayers());
		result.append(AcroServ.CR);
		result.append("Max players: " + maxplay);
		result.append(AcroServ.CR);
		result.append("Min letters: " + minacro);
		result.append(AcroServ.CR);
		result.append("Max letters: " + maxacro);
		result.append(AcroServ.CR);
		return result.toString();
	}
}
