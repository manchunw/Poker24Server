import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Stack;

import javax.jms.JMSException;
import javax.jms.Message;



/**
 * Server program for JPoker 24-Game
 * @author User
 */
public class JPoker24GameServer extends UnicastRemoteObject implements GameUserOp {

    private static final long serialVersionUID = -8673764664946506618L;
    private Connection conn;
    private static int state = 0;
    private JMSServer jmss;
    private Thread thread;

	/**
	 * Main program
	 * @param args arguments of program
	 */
    public static void main(String[] args) {
        try {
        	JMSServer jmss = new JMSServer();
        	JPoker24GameServer app = new JPoker24GameServer();
            System.setSecurityManager(new SecurityManager());
            Naming.rebind("GameServer", app);
            app.go(jmss, args);
            //System.out.println("Service registered");
        } catch(Exception e) {
            System.err.println("Exception thrown: "+e);
            e.printStackTrace();
        }
    }
    
	ArrayList<PlayerInfo> arr = new ArrayList<PlayerInfo>();

    private void go(JMSServer jmss, String[] args) throws Exception {
		// TODO Auto-generated method stub
		this.jmss = jmss;
    	try {
    		Class.forName("org.sqlite.JDBC").newInstance();
    		conn = DriverManager.getConnection("jdbc:sqlite:"+args[0]);
    		System.out.println("Database connected");
    		new Thread(new WaitPlayers()).start();
    	} catch (Exception e){
    		System.out.println("go error: "+e);
    		throw e;
    	}
		
	}
    
    class WaitPlayers implements Runnable {
    	
    	public WaitPlayers() throws JMSException{
    		jmss.start();
    	}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			try {
		    	while(true){
					System.out.println("Ready to receive message...");
					PlayerInfo playerInfo = jmss.receiveMessage();
					if (state == 0){
						thread = new Thread(new HandlePlayer(playerInfo));
						thread.start();
					} else new Thread(new HandlePlayer(playerInfo)).start();
		    	}
			} catch (JMSException e) {
				// TODO Auto-generated catch block
				System.out.println("Thread error: "+e);
			}
		}
    	
    }
    
    class HandlePlayer implements Runnable {
    	
    	private PlayerInfo playerInfo;
    	
    	public HandlePlayer(PlayerInfo playerInfo){
    		this.playerInfo = playerInfo;
    	}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			try {
				for(int i = 0; i < arr.size(); i++){
					if (arr.get(i).getName().equals(playerInfo.getName())){
						// already exists in array
						return;
					}
				}
				PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), gamePlayed, AVG(winTime) from userinfo, winhistory WHERE userinfo.userName = winhistory.userName AND userinfo.userName = ? GROUP BY userinfo.userName");
				stmt.setString(1, playerInfo.getName());
		        ResultSet rs = stmt.executeQuery();
		        while(rs.next()){
		        	playerInfo.setNumWin(rs.getInt(1));
		        	playerInfo.setGamePlayed(rs.getInt(2));
		        	playerInfo.setAvg(Math.round(rs.getDouble(3)*100)/100);
		        }
				arr.add(playerInfo);
				if (state == 0){ // first player comes in
					System.out.println("Receive first player: "+playerInfo);
					state = 1; // only 1 player
					try { Thread.sleep(10000); } catch (InterruptedException e){ return; }
					if (arr.size() >= 2){
						state = 0; // reset state
						// send back the tmparray of players
						System.out.println("Timeout! transmit message to start...");
						Message m = jmss.convertMsg(new NewGame(arr));
						jmss.broadcastMessage(m);
						arr.clear();
					} else { // only 1 player
						state = 2;
					}
				} else if (state == 1) { // second player comes in, not timeout
					System.out.println("Receive second player: "+playerInfo);
					System.out.println("Wait for more players before we start.");
					state = 3; // 2 or more players
				} else if (state == 2){ // second player comes in, timeout
					System.out.println("Receive second player: "+playerInfo);
					state = 0; // reset state
					// send back the tmparray of players
					System.out.println("Two players after timeout! Transmit message to start...");
					Message m = jmss.convertMsg(new NewGame(arr));
					jmss.broadcastMessage(m);
					arr.clear();
				} else if (state == 3) {
					System.out.println("Receive third player: "+playerInfo);
					state = 4;
				} else { // third or fourth player comes in (before timeout)
					// send back the tmparray of players
					System.out.println("Four players! Transmit message to start...");
					Message m = jmss.convertMsg(new NewGame(arr));
					thread.interrupt();
					state = 0; // reset state
					jmss.broadcastMessage(m);
					arr.clear();
				}
			} catch (JMSException | SQLException e){
				System.out.println("HandlePlayer error: "+e);
				e.printStackTrace();
			}
		}
    	
    }

	/**
     * Constructor of server
     * @throws RemoteException Exception when there is remote server problem
     */
	public JPoker24GameServer() throws RemoteException { }

    /**
     * Login function of client. Remote class fetched by RMI.
     */
	public synchronized int login(String username, String password) throws RemoteException, SQLException {
        // validate user from Userinfo.txt
		// avoid repeated login using OnlineUser.txt
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT userName, password, loginTime FROM userinfo");
        String currUsername = "", currPassword = "";
        boolean found = false;
        while(rs.next()){
            currUsername = rs.getString("userName");
            currPassword = rs.getString("password");
            if (currUsername.equals(username) && currPassword.equals(password)){
            	found = true;
            	int loginTime = rs.getInt("loginTime");
                if (loginTime != 0){
                	return 2;
                }
            	break;
            } else if (currUsername.equals(username)){
                return 1;
            }
        }
        if (!found) return 1;
        // update OnlineUser.txt
    	PreparedStatement stmt2 = conn.prepareStatement("UPDATE userinfo SET loginTime = datetime('now') WHERE username = ?");
    	stmt2.setString(1, currUsername);
    	stmt2.executeUpdate();
        return 0;
    }

    /**
     * Register function of client. Remote class fetched by RMI.
     */
	public synchronized int register(String username, String password) throws RemoteException, SQLException {
        // avoid duplicating username with UserInfo.txt
        PreparedStatement stmt = conn.prepareStatement("SELECT userName from userinfo WHERE userName = ?");
        stmt.setString(1, username);
        ResultSet rs = stmt.executeQuery();
        if (rs.next()){
        	return 1;
        }
        // insert the user record
        PreparedStatement stmt2 = conn.prepareStatement("INSERT INTO userinfo (userName, password, gamePlayed, loginTime) VALUES (?, ?, ?, datetime('now'))");
        stmt2.setString(1, username);
        stmt2.setString(2, password);
        stmt2.setInt(3, 0);
        stmt2.executeUpdate();
        return 0;
    }

    /**
     * Logout function of client. Remote class fetched by RMI.
     */
	public synchronized int logout(String username, ArrayList<PlayerInfo> players) throws RemoteException, SQLException {
        // update OnlineUser.txt
		PreparedStatement stmt = conn.prepareStatement("UPDATE userinfo SET loginTime = 0 WHERE userName = ?");
		stmt.setString(1, username);
		stmt.executeUpdate();
		updateGame(username, players);
        return 0;
    }
	
	public synchronized String [] [] userProfile(String username, ArrayList<PlayerInfo> players) throws RemoteException, SQLException {
		Statement stmt = conn.createStatement();
		String sql = "SELECT u2.userName, u2.loginTime, u2.gamePlayed, COUNT(w2.winTime) as win, AVG(w2.winTime) as avgWin, ( SELECT COUNT(*) FROM ( SELECT u1.userName AS name, COUNT(w1.winTime) as win, u1.gamePlayed, AVG(w1.winTime) as avgWin FROM userinfo AS u1 LEFT JOIN winhistory AS w1 ON u1.userName=w1.userName GROUP BY u1.userName ) t1 WHERE ( t1.win > count(w2.winTime) ) OR ( t1.win = count(w2.winTime) AND t1.gamePlayed < u2.gamePlayed ) OR ( t1.win = count(w2.winTime) AND t1.gamePlayed = u2.gamePlayed AND t1.avgWin < AVG(w2.winTime) ) )+1 as RANK FROM userinfo AS u2 LEFT JOIN winhistory AS w2 ON u2.userName=w2.userName WHERE u2.userName = '"+username+"' GROUP BY u2.userName";
		ResultSet rs = stmt.executeQuery(sql);
		String [] [] strArr = new String [1][6];
		while (rs.next()){
			strArr[0][0] = rs.getString(1);
			for (int i = 1; i <= 5; i++)
				strArr[0][i] = String.valueOf(rs.getInt(i + 1));
		}
		updateGame(username, players);
		return strArr;
	}
	
	public synchronized String [] [] leaderBoard(String username, ArrayList<PlayerInfo> players) throws RemoteException, SQLException {
		Statement stmt = conn.createStatement();
		String sql = "SELECT u2.userName, u2.loginTime, u2.gamePlayed, COUNT(w2.winTime) as win, AVG(w2.winTime) as avgWin, ( SELECT COUNT(*) FROM ( SELECT u1.userName AS name, COUNT(w1.winTime) as win, u1.gamePlayed, AVG(w1.winTime) as avgWin FROM userinfo AS u1 LEFT JOIN winhistory AS w1 ON u1.userName=w1.userName GROUP BY u1.userName ) t1 WHERE ( t1.win > count(w2.winTime) ) OR ( t1.win = count(w2.winTime) AND t1.gamePlayed < u2.gamePlayed ) OR ( t1.win = count(w2.winTime) AND t1.gamePlayed = u2.gamePlayed AND t1.avgWin < AVG(w2.winTime) ) )+1 as RANK FROM userinfo AS u2 LEFT JOIN winhistory AS w2 ON u2.userName=w2.userName GROUP BY u2.userName ORDER BY RANK ASC LIMIT 10";
		ResultSet rs = stmt.executeQuery(sql);
		String [] [] strArr = new String [10][5];
		int cnt = 0;
		while (rs.next()){
			strArr[cnt][0] = String.valueOf(rs.getInt(6));
			strArr[cnt][1] = rs.getString(1);
			strArr[cnt][2] = String.valueOf(rs.getInt(4));
			strArr[cnt][3] = String.valueOf(rs.getInt(3));
			strArr[cnt][4] = String.format("%.2f", rs.getFloat(5))+"s";
			++cnt;
		}
		updateGame(username, players);
		return strArr;
	}
	
	public synchronized void playGame(String username, ArrayList<PlayerInfo> players) throws RemoteException {
		updateGame(username, players);
	}
	
	private void updateGame(String username, ArrayList<PlayerInfo> players){
		if (players.size() > 0){
			for (int i = 0; i < players.size(); i++)
				if (players.get(i).getName().equals(username))
					players.remove(i);
			System.out.println("Only "+players.toString()+" are online");
			UpdateGame updateGame = new UpdateGame(players);
			try {
				Message m = jmss.convertMsg(updateGame);
				jmss.broadcastMessage(m);
			} catch (JMSException e){
				e.printStackTrace();
			}
		}
	}

	@Override
	public synchronized boolean compareWin(String username, NewGame newGame, String answer, long winTime) throws SQLException, RemoteException {
		// TODO Auto-generated method stub
		ParsePostFix ppf = parse(newGame, answer);
		if (ppf.correctNum){
			// resolve bracket
			String [] postfix = new String[7];
			int cnt = 0;
			Stack<String> opStack = new Stack<String>();
			for (int i = 0; i < ppf.parsearr.size(); i++){
				try {
					Integer.parseInt(ppf.parsearr.get(i));
					postfix[cnt++] = ppf.parsearr.get(i);
				} catch (NumberFormatException e){
					if (ppf.parsearr.get(i).equals("(")){
			            opStack.push("(");
					} else if (ppf.parsearr.get(i).equals(")")) {
			            while(!myPeek(opStack).equals("(")){
			            	postfix[cnt++] = myPop(opStack);
			            }
			            myPop(opStack);
			        } else {
			            while (importance(ppf.parsearr.get(i)) <= importance(myPeek(opStack))) {
			                postfix[cnt++] = myPop(opStack);
			            }
			            opStack.push(ppf.parsearr.get(i));
			        }
				}
				
			}
			System.out.print("postfix: ");
			for (String s : postfix)
				System.out.print(s+" ");
			// finally resolve equation
			double result = new PostFix(postfix).evaluate();
			System.out.println("= "+result);
			if (Math.round(result*10000)/10000 == 24.0){ // correct answer
				System.out.println("Correct answer.");
				int idx = newGame.searchByUsername(username);
				ArrayList<PlayerInfo> players = new ArrayList<PlayerInfo>();
				if (idx > -1){
					players = newGame.getPlayers();
					for (int i = 0; i < players.size(); i++){
						PlayerInfo player = players.get(i);
						player.setGamePlayed(player.getGamePlayed()+1);
						if (i == idx) {
							// set average time to resolve
							player.setAvg((player.getAvg()*player.getNumWin()+winTime)/(player.getNumWin()+1));
							// increment winner win number
							player.setNumWin(player.getNumWin()+1);
						}
						players.set(i, player);
					}
				}
				// update database
				PreparedStatement stmt = conn.prepareStatement("INSERT INTO winHistory (username, winTime) VALUES (?, ?)");
		    	stmt.setString(1, username);
	        	stmt.setFloat(2, winTime);
	        	stmt.executeUpdate();
	        	PreparedStatement stmt2 = conn.prepareStatement("UPDATE userinfo SET gamePlayed = gamePlayed + 1 WHERE userName = ?");
	    		for (PlayerInfo player : players){
	    			stmt2.setString(1, player.getName());
		    		stmt2.executeUpdate();
	    		}
				EndGame endGame = new EndGame(players, username, answer);
				try {
					Message m = jmss.convertMsg(endGame);
					jmss.broadcastMessage(m);
				} catch (JMSException e){
					e.printStackTrace();
				}
				return true;
			} else return false; // incorrect answer
		} else return false; // invalid answer format
	}
	
	private int importance(String op){
		if (op.equals("*") || op.equals("/"))
			return 2;
		else if (op.equals("+") || op.equals("-"))
			return 1;
		else return 0;
	}
	
	private String myPeek(Stack<String> opStack) {
		// TODO Auto-generated method stub
		if (opStack.empty()) return "empty";
	    else return opStack.peek();
	}
	
	private String myPop(Stack<String> opStack) {
		// TODO Auto-generated method stub
		if (opStack.empty()) return "empty";
	    else return opStack.pop();
	}

	public class ParsePostFix {
		public ArrayList<String> testcase;
		public ArrayList<String> testops;
		public ArrayList<String> parsearr;
		public boolean correctNum;
		@Override
		public String toString() {
			// TODO Auto-generated method stub
			String str = "";
			str += "testcase: ";
			for (String tc : testcase)
				str += tc + ",";
			str += "\ntestops: ";
			for (String to : testops)
				str += to + ",";
			str += "\nparsearr: ";
			for (String tc : parsearr)
				str += tc + ",";
			str += "\ncorrectNum: "+correctNum;
			return str;
		}
	}
	
	private ParsePostFix parse(NewGame newGame, String answer) {
		boolean correctNum = true, needOperator = false;
		// store all the numerical values
		ArrayList<String> testcasearr = new ArrayList<String>(), testopsarr = new ArrayList<String>(), parsearr = new ArrayList<String>();
		ArrayList<Integer> openbracketarr = new ArrayList<Integer>(), closebracketarr = new ArrayList<Integer>();
		Stack<Integer> tmpopenbracketarr = new Stack<Integer>();
		int value = 0;
		boolean [] compare = new boolean [4];
		int closeBracket = 0;
		for (int i = 0; i < 4; i++) compare[i] = false;
		String [] cards = newGame.getCards();
		// set as a temporary operator to cater for the last value
		try {
			parsearr.add("(");
			for (int i = 0; i < answer.length() && correctNum; i++){
				try {
					// integer value: add to value
					int n = Integer.parseInt(answer.substring(i, i+1));
					value = value * 10 + n;
					needOperator = true;
				} catch (NumberFormatException e) {
					// ignore white space and brackets
					if (answer.substring(i, i+1).equals(" "))
						continue;
					else if  (answer.substring(i, i+1).equals("(") && !needOperator){
						tmpopenbracketarr.push(testcasearr.size());
						parsearr.add("(");
					} else if (answer.substring(i, i+1).equals(")") && needOperator){
						if (tmpopenbracketarr.size() > 0)
							openbracketarr.add(tmpopenbracketarr.pop());
						else {
							correctNum = false;
							break;
						}
						// operator is usually behind close bracket, value is found later
						closebracketarr.add(testcasearr.size() + 1);
						closeBracket++;
					}
					// position i is an operator
					else if (value >= 1 && value <= 13 && needOperator){
						testcasearr.add(String.valueOf(value));
						parsearr.add(String.valueOf(value));
						while(closeBracket > 0){
							closeBracket--;
							parsearr.add(")");
						}
						testopsarr.add(answer.substring(i, i+1));
						parsearr.add(answer.substring(i, i+1));
						needOperator = false;
						// reset value to 0
						value = 0;
					} else {
						// value either <1 or >13
						// value = 0: when two operators are together
						//System.out.println("correctNum = false: "+answer.substring(i, i+1)+" at "+i);
						correctNum = false;
						break;
					} 
				}
			}
			// take care of the last value if no bracket
			if (value >= 1 && value <= 13 && needOperator){
				testcasearr.add(String.valueOf(value));
				parsearr.add(String.valueOf(value));
				while(closeBracket > 0){
					closeBracket--;
					parsearr.add(")");
				}
				needOperator = false;
				// reset value to 0
				value = 0;
			}
			//System.out.println(correctNum);
			// check number of terms
			if (testcasearr.size() != 4)
				correctNum = false;
			parsearr.add(")");
			if (testopsarr.size() != 3)
				correctNum = false;
			if (openbracketarr.size() != closebracketarr.size())
				correctNum = false;
			//System.out.println("tmpopenbracketarr - "+tmpopenbracketarr.toString());
			if (tmpopenbracketarr.size() != 0)
				correctNum = false;
			//System.out.println(correctNum);
			if (correctNum){
				for (int i = 0; i < 3; i++)
					if (!(testopsarr.get(i).equals("+") ||
							testopsarr.get(i).equals("-") ||
							testopsarr.get(i).equals("*") ||
							testopsarr.get(i).equals("/")))
						correctNum = false;
			}
			// check duplicate: check whether there are incorrectly repeated terms
			if (correctNum){
				for (int i = 0; i < testcasearr.size(); i++)
					for (int j = 0; j < 4; j++)
						if (String.valueOf(testcasearr.get(i)).equals(cards[j].substring(1)) && compare[j] == false){
							compare[j] = true;
						}
				for (int i = 0; i < 4; i++)
					if (!compare[i]){
						correctNum = false;
						break;
					}
			}
		} catch (Exception e){
			correctNum = false;
		}
		//System.out.println(correctNum);
		ParsePostFix ppf = new ParsePostFix();
		ppf.correctNum = correctNum;
		ppf.testcase = testcasearr;
		ppf.testops = testopsarr;
		ppf.parsearr = parsearr;
		System.out.println(ppf);
		return ppf;
	}

}
