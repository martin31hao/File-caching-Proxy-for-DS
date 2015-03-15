/*
 * Chunk.java
 * This class is used to transfer chunks of data between server and proxy
 * It needs to be serializable because of RMI
 *
 * @author  : Xinkai Wang
 * @contact : xinkaiw@andrew.cmu.edu
 */
public class Chunk implements java.io.Serializable {
    public byte[] content; // content to be transferred
    public int size; // size of the data
	
    public Chunk(int size) {
        this.size = size;
        content = new byte[size];
    }
}
