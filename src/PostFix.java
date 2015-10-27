import java.util.Stack;

public class PostFix {

	private String[] tokens; 
	public PostFix(String [] tokens) {
		this.tokens = tokens;
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		for(String s: tokens) {
			sb.append(" "+s);
		}
		sb.deleteCharAt(0);
		return sb.toString();
	}
	
	public double evaluate() {
		// Assume that the PostFix is always valid
		Stack<Double> stack = new Stack<Double>();
		for(String token: tokens) {
			if(token.equals("+")) {
				stack.push(stack.pop()+stack.pop());	
			} else if(token.equals("-")) {
                stack.push(-stack.pop()+stack.pop());
			} else if(token.equals("*")) {
				stack.push(stack.pop()*stack.pop());
			} else if(token.equals("/")) {
				stack.push(1/stack.pop()*stack.pop());
			} else if(token.equals("^")) {
				stack.push(Math.pow(stack.pop(),stack.pop()));
			} else {
                stack.push(Double.valueOf(token));
			}
		}
		return stack.pop();
	}
}
