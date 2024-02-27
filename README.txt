HOW TO SET PROPERTIES CONFIG FILE
-----------------------------------------------

For smaller networks (e.g, up to 1000 nodes) the default config files should work without modifications on any machine.
Just be sure to set max_threads less than the allowed threads on your machine. 
For linux/macos, you can check the output of the command:
ulimit -u

Bigger networks (>1000 nodes) may require some tricks:

1) increase the heap memory, by passing -Xmx option to the JVM increase it.
2) increase the bootstrap_blocks period to 1000 or more, to avoid congestion of queues (even if this don't solve the memory issue)
2) decreasing the value of p2p_max_hops from 3 to 2 or less. Be aware that a lower value means a more incomplete channel graph.


