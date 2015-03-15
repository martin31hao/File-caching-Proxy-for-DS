/*
 * Chunk.java
 * This class is used to transfer chunks of data between server and proxy
 */
public class Chunk implements java.io.Serializable {
	public byte[] content;
	public int size; // size of the data
	
	public Chunk(int size) {
		this.size = size;
		content = new byte[size];
	}
}
