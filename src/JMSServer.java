import java.io.Serializable;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.naming.NamingException;


public class JMSServer {
	private JMSHelper jmsHelper;
	private MessageConsumer queueReader;
	private MessageProducer topicSender;
	
	public JMSServer() throws NamingException, JMSException {
		jmsHelper = new JMSHelper();
	}
	public void start() throws JMSException {
		queueReader = jmsHelper.createQueueReader();
		topicSender = jmsHelper.createTopicSender();
	}
	public PlayerInfo receiveMessage() throws JMSException {
		System.out.println("Start receiving message...");
		Message jmsMessage = queueReader.receive();
		PlayerInfo player = (PlayerInfo)((ObjectMessage)jmsMessage).getObject();
		System.out.println("Received: "+player);
		return player;
	}
	public void broadcastMessage(Message jmsMessage) throws JMSException {
		topicSender.send(jmsMessage);
	}
	public Message convertMsg(Serializable obj) throws JMSException {
		return jmsHelper.createMessage(obj);
	}
}
