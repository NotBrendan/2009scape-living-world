package core.net.event;

import core.net.IoReadEvent;
import core.net.IoSession;
import core.net.lobby.WorldList;
import core.net.registry.AccountRegister;
import core.tools.Log;
import core.tools.RandomFunction;
import core.tools.SystemLogger;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static core.api.ContentAPIKt.log;

/**
 * Handles handshake read events.
 * @author Emperor
 */
public final class HSReadEvent extends IoReadEvent {

	// debug
	static Map<String, Integer> count = new HashMap<>();

	/**
	 * Constructs a new {@code HSReadEvent}.
	 * @param session The session.
	 * @param buffer The buffer.
	 */
	public HSReadEvent(IoSession session, ByteBuffer buffer) {
		super(session, buffer);
	}

	@Override
	public void read(IoSession session, ByteBuffer buffer) {
		if (!buffer.hasRemaining()) {
			queueBuffer();
			return;
		}
		int opcode = buffer.get() & 0xFF;
		// TCP may deliver one handshake/registration frame across multiple reads.
		// Keep the opcode with the partial payload until the entire frame arrives.
		int required = -1;
		switch (opcode) {
		case 14: required = 1; break;
		case 15:
		case 255: required = 4; break;
		case 147: required = 6; break;
		case 36:
			if (!buffer.hasRemaining()) { queueBuffer(opcode); return; }
			required = 1 + (buffer.get(buffer.position()) & 0xFF);
			break;
		case 186:
			boolean terminated = false;
			for (int i = buffer.position(); i < buffer.limit(); i++) {
				if (buffer.get(i) == 0) { terminated = true; break; }
			}
			if (!terminated) {
				if (buffer.remaining() > 255) { session.disconnect(); return; }
				queueBuffer(opcode);
				return;
			}
			break;
		default: break;
		}
		if (required >= 0 && buffer.remaining() < required) {
			queueBuffer(opcode);
			return;
		}
		Integer amount = count.get(session.getAddress());
		if (amount == null) {
			amount = 0;
		}
		count.put(session.getAddress(), amount + 1);
		switch (opcode) {
		case 14:
			session.setNameHash(buffer.get() & 0xFF);
			session.setServerKey(RandomFunction.RANDOM.nextLong());
			session.write(true);
			break;
		case 15:
			int revision = buffer.getInt();
			//int sub_revision = buffer.getInt();
			buffer.flip();

			if (revision != 530 ){//|| sub_revision != Constants.CLIENT_BUILD) {
				session.disconnect();
				break;
			}
			session.write(false);
			break;
		case 147:
		case 186:
		case 36:
			AccountRegister.read(session, opcode, buffer);
			break;
		case 255: // World list
			int updateStamp = buffer.getInt();
			WorldList.sendUpdate(session, updateStamp);
			break;
		default:
			log(this.getClass(), Log.FINE, "PKT " + opcode);
			session.disconnect();
			break;
		}
	}

}
