//TODO: 
//history is a bit wonky (spam, topics, etc)
//line breaks are weird for voting results

package org.chernovia.net.games.parlour.acro.server;

import java.util.List;
import java.util.StringTokenizer;
import java.util.Vector;

import org.chernovia.lib.misc.MiscUtil;
import org.chernovia.lib.netgames.db.GameData;
import org.chernovia.lib.netgames.roomserv.*;

public class AcroServ implements ConnListener {
	static final String VERSION = "0.1";
	static String CR;
	static String DATAFILE = "/acrodata.txt";
	static String ACROLOG = "/acrolog.txt";
	static String MGRFILE = "/managers.txt";
	static String TOPFILE = "/topics.txt";
	static String HELPFILE = "/acrohelp.txt";
	static String ABCDEF = "/deflet";
	String acroCmdPfx = "!", mgrCmdPfx = "~";
	AcroGame[] games;
	NetServ serv; 
	long floodGate = 1500; 
	int floodLimit = 3, floodTimeout = 99;
	List<Flooder> floodList;
	
	public static class Flooder {
		Connection conn;
		int times;
		public Flooder(Connection c) {
			conn = c; times = 1;
		}
	}
	
	public Flooder getFlooder(Connection c) {
		for (Flooder f: floodList)
			if (f.conn.equals(c))
				return f;
		return null;
	}

	/**
	 * Constructs the AcroBot server
	 * @param name		IRC name of the bot
	 * @param host		IRC server host name
	 * @param oauth		OAuth authentication key
	 * @param channel	IRC channel to moderate
	 */
	public AcroServ(String name, String host, String oauth, String channel) {
		floodList = new Vector<Flooder>();
		initTwitch(name,host,oauth,channel);
	}
	
	public void initTwitch(String name, String host, String oauth, String channel) {
		CR = NetServ.newline[NetServ.IRC] = "|";
		serv = new TwitchServ(name,host,oauth,channel,this);
		startGames(1);
	}
	
	public void initLocal(int port) {
		CR = NetServ.newline[NetServ.LOCAL];
		serv = new Loc_Serv(port,this);
		startGames(12);
	}
	
	//TODO: games on different IRC channels
	public void startGames(int n) {
		serv.setMaxChannels(n); games = new AcroGame[n];
		for (int i=0;i<games.length;i++) {
			games[i] = new AcroGame(serv,i); //chan 0 = lobby for local games
			if (serv.getType() != NetServ.LOCAL || i > 0)
				new Thread(games[i]).start();
		}
	}

	public static void main(String[] args) {
		AcroServ S = new AcroServ(args[0],args[1],args[2],args[3]);
		AcroBase.CR = CR;
		AcroBase.DATAFILE = DATAFILE;
		GameData.initData(AcroBase.initFields());
		AcroBase.editStats( //just a test
		new GameData(AcroBase.newPlayer("Zippy")));
		S.serv.startSrv();
	}
	
	public boolean floodChk(Connection conn) {
		if (conn.idleTime() < floodGate) {
			Flooder f = getFlooder(conn); 
			if (f != null) {
				if (f.times++ > floodLimit) {
					conn.tell("You have been banned for excess flooding. Augh.");
					conn.ban(floodTimeout);
				}
				else conn.tell("Flood warning (" + f.times + "x)");
			}
			else {
				conn.tell("Please don't send messages less than " + 
					floodGate + " milliseconds apart.");
					floodList.add(new Flooder(conn));
			}
			return true;
		}
		else {
			Flooder f = getFlooder(conn); if (f != null) floodList.remove(f);
			return false;
		}
	}

	//only for direct tells/whispers
	public boolean newMsg(Connection conn, String msg) {
		System.out.println("New Message: " + conn + ": " + msg + ", idle: " + conn.idleTime());
		if (floodChk(conn)) return true;
		AcroGame game = conn.getChan() >= 0 ? games[conn.getChan()] : null;
		try {
			if (msg.equals("") || msg.equals(mgrCmdPfx) || msg.equals(acroCmdPfx)) { 
				conn.tell("Eh?"); 
			}
			else if (msg.equals("?")) conn.tell(showCmds(),true,false);
			else if (msg.startsWith(mgrCmdPfx)) {
				mgrCmd(game,conn,msg.substring(1));
			}
			else if (msg.startsWith(acroCmdPfx)) {
				acroCmd(game,conn,msg.substring(1));
			}
			else {
				if (game == null) {
					conn.tell("Please enter a channel");
				}
				else if (game.getMode() == AcroGame.MOD_PAUSE) {
					conn.tell("Paused right now.");
				}
				else gameTell(game,conn,msg);
				return true;
			}
			return false;
		}
		catch (Exception augh) {
			serv.broadcast("Augh: " + augh.getMessage());
			augh.printStackTrace();
			return false;
		}
	}

	private void gameTell(AcroGame game, Connection conn, String msg) {
		switch(game.getMode()) {
		case AcroGame.MOD_ACRO:
			if (!game.isLegal(msg.toUpperCase()))
				conn.tell("Illegal acro: " + msg);
			else game.newAcro(conn,msg);
			break;
		case AcroGame.MOD_VOTE: 
			int i = MiscUtil.strToInt(msg);
			if (i > 0 && game.getNumAcros() >= i) {
				game.newVote(conn,i-1);
			}
			else conn.tell("Bad vote.");
			break;
		case AcroGame.MOD_WAIT:
			int p = game.getPlayer(conn.getHandle());
			if (game.TOPICS && p >= 0 && game.getLastWin() == p) {
				game.newTopic(p,msg);
			}
			else conn.tell("Next round coming...");
			break;
		case AcroGame.MOD_NEW:
			conn.tell("New game coming...");
			break;
		default:
			conn.tell("Idle. Tell me " + acroCmdPfx + "start.");
		}
	}

	private void mgrCmd(AcroGame game, Connection conn, String cmd) {
		String handle = conn.getHandle();
		if (AcroBase.searchFile(handle, MGRFILE) < 0 &&
		(game == null || conn != game.getManager())) {
			conn.tell("You're not a manager.");
			return;
		}
		//general manager commands
		if (cmd.toUpperCase().startsWith("SPOOF") && cmd.length() > 7) {
			String m = cmd.substring(6);
			serv.send(m);
			conn.tell("Spoofed: " + m);
			return;
		}
		else if (cmd.equalsIgnoreCase("LETFILES")) {
			conn.tell(AcroLetter.listFiles(),true,false); return;
		}
		//else if (cmd.equalsIgnoreCase("OFF")) {	System.exit(-1); }
		if (game == null) {
			conn.tell("No channel!"); return;
		}
		StringTokenizer tokens = new StringTokenizer(cmd);
		String s = tokens.nextToken();
		switch (tokens.countTokens())  {
		case 0:
			if (s.equalsIgnoreCase("RESET")) {
				if (game.getMode() != AcroGame.MOD_PAUSE) {
					game.setMode(AcroGame.MOD_RESET);
					game.stop();
				}
				else conn.tell("First unpause me.");
			}
			else if (s.equalsIgnoreCase("IDLE")) {
				if (game.getMode() != AcroGame.MOD_PAUSE) {
					game.setMode(AcroGame.MOD_IDLE);
					game.stop();
				}
				else conn.tell("First unpause me.");
			}
			else if (s.equalsIgnoreCase("PAUSE")) {
				boolean wasIdling =
					(game.getMode() == AcroGame.MOD_IDLE);
				if (game.getMode() != AcroGame.MOD_PAUSE)
					game.setMode(AcroGame.MOD_PAUSE);
				if (!wasIdling) game.stop();
				else serv.tch(game.getChan(),"Paused.",false,false);
			}
			else if (s.equalsIgnoreCase("NEXT")) {
				if (game.getMode() > AcroGame.MOD_IDLE) {
					game.stop();
				}
				else conn.tell("Invalid Game State.");
			}
			else if (s.equalsIgnoreCase("SHOUTING")) {
				game.SHOUTING = !game.SHOUTING;
				serv.tch(game.getChan(),"Shouting: " + game.SHOUTING,false,false);
			}
			else if (s.equalsIgnoreCase("FLATTIME")) {
				game.FLATTIME = !game.FLATTIME;
				serv.tch(game.getChan(),"Flat time: " + game.FLATTIME,false,false);
			}
			else if (s.equalsIgnoreCase("REVEAL")) {
				game.REVEAL = !game.REVEAL;
				serv.tch(game.getChan(),"Reveal: " + game.REVEAL,false,false);
			}
			else if (s.equalsIgnoreCase("TOPICS")) {
				game.TOPICS = !game.TOPICS;
				serv.tch(game.getChan(),"Topics: " + game.TOPICS,false,false);
			}
			else if (s.equalsIgnoreCase("TIEBONUS")) {
				game.TIEBONUS = !game.TIEBONUS;
				serv.tch(game.getChan(),"TieBonus: " + game.TIEBONUS,false,false);
			}
			else if (s.equalsIgnoreCase("ADULT")) {
				game.ADULT = !game.ADULT;
				serv.tch(game.getChan(),"Adult: " + game.ADULT,false,false);
			}
			else if (s.equalsIgnoreCase("DUMP")) {
				conn.tell(game.toString(),true,false);
			}
			else conn.tell("Oops: no such command.");
			break;
		case 1:
			String token = tokens.nextToken();
			//int i = Integer.parseInt(a);
			if (s.equalsIgnoreCase("LOADLET")) {
				game.newLetters(token);
			}
			else if (serv.getType() == NetServ.IRC && s.equalsIgnoreCase("CR")) { 
				((TwitchServ)serv).setTwitchCR(" " + token + " ");
			}
			//TODO: implement these!
			/*else if (s.equalsIgnoreCase("CHANNEL")) {
				serv.tch(G.getChan(),"New Channel: " + i,false);
				G.setChan(i);
			}
			else if (s.equalsIgnoreCase("ACROTIME")) {
				G.acrotime = i;
				serv.tch(G.getChan(),"Acro Time: " + i,false);
			}
			else if (s.equalsIgnoreCase("VOTETIME")) {
				G.votetime = i;
				serv.tch(G.getChan(),"Vote Time: " + i,false);
			}
			else if (s.equalsIgnoreCase("WAITTIME")) {
				G.waittime = i;
				serv.tch(G.getChan(),"Wait Time: " + i,false);
			} */
			else conn.tell("D'oh: No such command.");
			break;
		default: conn.tell("Too many tokens!");
		}
	}

	private void acroCmd(AcroGame G, Connection conn, String msg) {
		String handle = conn.getHandle();
		StringTokenizer tokens = new StringTokenizer(msg);
		String s = tokens.nextToken();
		switch (tokens.countTokens())  {
		case 0:
			if (s.equalsIgnoreCase("HELP")) {
				//conn.tell(AcroBase.listFile(HELPFILE),true);
				conn.tell("Rules: each round a randomly generated acronym " + 
				"is created.  First, enter your own expansion by whispering " +
				"'/w ZugNet (your acronym)'.  Then, all acronyms are voted upon, and " +
				"you can vote for one like so: '/w ZugNet (acro number).  GLHF!");
			}
			else if (s.equalsIgnoreCase("START"))  {
				if (G == null) {
					conn.tell("No channel!");
				}
				else if (G.getMode() == AcroGame.MOD_IDLE) {
					G.setManager(conn);
					G.setMode(AcroGame.MOD_ACRO);
					G.stop();
				}
				else if (G.getMode() >= AcroGame.MOD_NEW) {
					conn.tell("Already playing!");
				}
				else conn.tell("Current mode: " + G.getMode());
			}
			else if (s.equalsIgnoreCase("WHO")) {
				conn.tell(serv.who(),true,false);
			}
			else if (s.equalsIgnoreCase("VERSION")) {
				conn.tell("Version "+AcroServ.VERSION);
			}
			else if (s.equalsIgnoreCase("VARS") ||
					s.equalsIgnoreCase("INFO")) {
				if (G == null) conn.tell("No channel!");
				else {
					if (conn.isGUI()) G.dumpAll(conn); 
					else conn.tell(G.listVars(),true,false);
				}
			}
			else if (s.equalsIgnoreCase("LETTERS")) {
				if (G == null) conn.tell("No channel!");
				else conn.tell(G.showLetters(),true,false);
			}
			else if (s.equalsIgnoreCase("ACRO")) {
				if (G == null) conn.tell("No channel!");
				else conn.tell("Current Acro: " + G.getAcro());
			}
			else if (s.equalsIgnoreCase("FINGER")) {
				conn.tell(AcroBase.statLine(
				AcroBase.getStats(handle,null)),true,false);
			}
			else if (s.equalsIgnoreCase("TOPTEN")) {
				conn.tell(AcroBase.topTen("wins"),true,false);
			}
			else if (s.equalsIgnoreCase("MANAGERS")) {
				conn.tell(AcroBase.listFile(MGRFILE),true,false);
			}
			else conn.tell("Bad command. Erp.");
			break;
		case 1:
			if (s.equalsIgnoreCase("FINGER")) {
				conn.tell(AcroBase.statLine(
				AcroBase.getStats(tokens.nextToken(),null)),true,false);
			}
			else if (s.equalsIgnoreCase("TOPTEN")) {
				conn.tell(AcroBase.topTen(
				tokens.nextToken()),true,false);
			}
			else conn.tell("Bad command. Erp.");
			break;
		default: conn.tell("Too many tokens!");
		}
	}
	
	public String showCmds() {
		return "Commands: " + CR +
			mgrCmdPfx + "off" + CR +
			mgrCmdPfx + "idle" + CR +
			mgrCmdPfx + "reset" + CR +
			mgrCmdPfx + "pause" + CR +
			mgrCmdPfx + "shouting" + CR +
			mgrCmdPfx + "flattime" + CR +
			mgrCmdPfx + "reval" + CR +
			mgrCmdPfx + "topics" + CR +
			mgrCmdPfx + "tiebonus" + CR +
			mgrCmdPfx + "letfiles" + CR +
			mgrCmdPfx + "loadlet" + CR +
			mgrCmdPfx + "channel" + CR +
			mgrCmdPfx + "acrotime" + CR +
			mgrCmdPfx + "votetime" + CR +
			mgrCmdPfx + "waittime" + CR +
			mgrCmdPfx + "dump" + CR +
			acroCmdPfx + "help" + CR +
			acroCmdPfx + "start" + CR +
			acroCmdPfx + "ver" + CR +
			acroCmdPfx + "vars" + CR +
			acroCmdPfx + "letters" + CR +
			acroCmdPfx + "acro" + CR +
			acroCmdPfx + "finger" + CR +
			acroCmdPfx + "topten" + CR +
			"";
	}

	@Override
	public void loggedIn(Connection conn) {
		if (serv.getType() == NetServ.LOCAL) {
			((Loc_Conn)conn).setPrompt(">");
			conn.tell("Welcome, " + conn.getHandle() + "!");
			conn.tell("Commands: who, shout (msg), ch (channel)");
		}
	};
	@Override
	public void disconnected(Connection conn) {};
}
