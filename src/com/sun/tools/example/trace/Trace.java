package com.sun.tools.example.trace;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Field;
import com.sun.jdi.PathSearchingVirtualMachine;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;

/*
 * This program traces the execution of another program.
 * It is a simple example of the use of the Java Debug Interface and relies
 * on the library "com.sun.jdi.*"; see $JAVA_HOME/lib/tools.jar.
 *
 * See "java Tracer -help" or "java -jar trace.jar -help" for usage
 *
 * It is based on Trace.java:
 * @version     @(#) Trace.java 1.5 03/12/19 00:23:13
 * @author Robert Field
 */

public final class Trace {

	// Running remote VM
	private final VirtualMachine vm;

	// Thread transferring remote error stream to our error stream
	private Thread errThread = null;

	// Thread transferring remote output stream to our output stream
	private Thread outThread = null;

	// Mode for tracing the Trace program (default= 0 off)
	private int debugTraceMode = 0;

	// Do we want to watch assignments to fields
	private boolean watchFields = false;

	// Do we want to break at all lines
	private boolean breakAtLines = false;

	// Class patterns for which we don't want events
	private String[] excludes = { "java.*", "javax.*", "sun.*", "com.sun.*" };

	//追加したところ
	private String methodName;
	private String declaringType;
	private String returnType;
	private List<String> argumentType;
	private Field fieldName;
	private Value valueName;

	/**
	 * main
	 */
	public static void main(String[] args) {
		String[] tracePrograms = { "HelloWorld" };
		Trace trace = new Trace(tracePrograms);

	}

	/**
	 * Parse the command line arguments. Launch target VM. Generate the trace.
	 */
	public Trace(final String[] args) {
		System.err.println("Trace.");
		System.err
				.println("  Requires Internet to access Sun's tools.jar files.");
		System.err
				.println("  Requires class file (compiling with -g gives more info) in current directory.");

		/* VAR */PrintWriter writer = new PrintWriter(System.out);
		/* VAR */int inx;
		for (inx = 0; inx < args.length; ++inx) {
			final String arg = args[inx];
			if (arg.charAt(0) != '-')
				break;
			if (arg.equals("-output")) {
				try {
					writer = new PrintWriter(new FileWriter(args[++inx]));
				} catch (IOException exc) {
					System.err.println("Cannot open output file: " + args[inx]
							+ " - " + exc);
					System.exit(1);
				}
			} else if (arg.equals("-all")) {
				excludes = new String[0];
			} else if (arg.equals("-fields")) {
				watchFields = true;
			} else if (arg.equals("-break")) {
				breakAtLines = true;
			} else if (arg.equals("-dbgtrace")) {
				debugTraceMode = Integer.parseInt(args[++inx]);
			} else if (arg.equals("-help")) {
				usage();
				System.exit(0);
			} else {
				System.err.println("No option: " + arg);
				usage();
				System.exit(1);
			}
		}

		if (inx >= args.length) {
			System.err.println("<class> missing");
			usage();
			System.exit(1);
		}

		final String className = args[inx];
		System.err.println("Name of class to trace: " + className);

		final StringBuilder sb = new StringBuilder();
		sb.append(args[inx]);
		for (++inx; inx < args.length; ++inx) {
			sb.append(' ');
			sb.append(args[inx]);
		}

		vm = launchTarget(sb.toString());
		System.err.println("Virtual machine's version = " + vm.version());
		if (vm instanceof PathSearchingVirtualMachine) {
			System.err.println("Virtual machine's class path = "
					+ ((PathSearchingVirtualMachine) vm).classPath());
		}
		generateTrace(writer);
	}

	/**
	 * Generate the trace. Enable events, start thread to display events, start
	 * threads to forward remote error and output streams, resume the remote VM,
	 * wait for the final event, and shutdown.
	 */
	void generateTrace(final PrintWriter writer) {
		vm.setDebugTraceMode(debugTraceMode);
		final Map<String, Boolean> options = new HashMap<String, Boolean>();
		options.put("watchFields", watchFields);
		options.put("breakAtLines", breakAtLines);
		EventThread eventThread = new EventThread(vm, writer, excludes, options);
		eventThread.setEventRequests();
		eventThread.start();

		methodName = setMethodName(eventThread.getMethodName());
		declaringType = setDeclaringType(eventThread.getDeclaringType());
		returnType = setReturnType(eventThread.getReturnType());
		argumentType = setArgumentType(eventThread.getArgumentType());
		fieldName = setFieldName(eventThread.getField());
		valueName = setValueName(eventThread.getValue());

		redirectOutput(vm.process());
		vm.resume();

		// Shutdown begins when event thread terminates
		try {
			eventThread.join();
			errThread.join(); // Make sure output is forwarded
			outThread.join(); // before we exit
		} catch (InterruptedException exc) {
			// we don't interrupt
		}
		writer.close();
	}

	/**
	 * Find a com.sun.jdi.CommandLineLaunch connector
	 */
	private static LaunchingConnector findLaunchingConnector() {
		// System.err.println
		// (Bootstrap.virtualMachineManager().launchingConnectors().toString());
		for (LaunchingConnector connector : Bootstrap.virtualMachineManager()
				.launchingConnectors()) {
			if (connector.name().equals("com.sun.jdi.CommandLineLaunch")) {
				System.err.println(connector.description());
				return connector;
			}
		}
		throw new Error("No launching connector");
	}

	/**
	 * Return the launching connector's arguments.
	 */
	private static Map<String, Connector.Argument> connectorArguments(
			final LaunchingConnector connector, final String mainArgs) {
		final Map<String, Connector.Argument> arguments = connector
				.defaultArguments();

		final Connector.Argument mainArg = arguments.get("main");
		mainArg.setValue(mainArgs);

		/*
		 * final Connector.Argument optionArg = arguments.get("options");
		 * optionArg.setValue("-cp "+ System.getProperty("java.class.path"));
		 */

		return arguments;
	}

	/**
	 * Launch target VM. Forward target's output and error.
	 */
	private static VirtualMachine launchTarget(final String mainArgs) {
		final LaunchingConnector connector = findLaunchingConnector();
		final Map<String, Connector.Argument> arguments = connectorArguments(
				connector, mainArgs);
		try {
			return connector.launch(arguments);
		} catch (IOException exc) {
			throw new Error("Unable to launch target VM: " + exc);
		} catch (IllegalConnectorArgumentsException exc) {
			throw new Error("Internal error: " + exc);
		} catch (VMStartException exc) {
			throw new Error("Target VM failed to initialize: "
					+ exc.getMessage());
		}
	}

	private void redirectOutput(Process process) {
		// Copy target's output and error to our output and error.
		errThread = new StreamRedirectThread("error reader",
				process.getErrorStream(), System.err);
		outThread = new StreamRedirectThread("output reader",
				process.getInputStream(), System.out);
		errThread.start();
		outThread.start();
	}

	/**
	 * Print command line usage help
	 */
	public static void usage() {
		System.err.println("Usage: java Trace <options> <class> <args>");
		System.err.println("<options> are:");
		System.err.println("  -output <filename>   Output trace to <filename>");
		System.err
				.println("  -all                 Include system classes in output");
		System.err.println("  -fields              Watch fields");
		System.err.println("  -break               Break at all lines");
		System.err.println("  -help                Print this help message");
		System.err
				.println("<class> is the program (class file) to trace; must be in current directory");
		System.err.println("<args> are the arguments to <class>");
	}

	public String getMethodName() {
		return methodName;
	}

	public String setMethodName(String methodName) {
		return this.methodName = methodName;
	}

	public String getDeclaringType() {
		return declaringType;
	}

	public String setDeclaringType(String declaringType) {
		return this.declaringType = declaringType;
	}

	public String getReturnType() {
		return returnType;
	}

	public String setReturnType(String returnType) {
		return this.returnType = returnType;
	}

	public List<String> getArgumentType() {
		return argumentType;
	}

	public List<String> setArgumentType(List<String> argumentType) {
		return this.argumentType = argumentType;
	}

	public Field getFieldName() {
		return fieldName;
	}

	public Field setFieldName(Field fieldName) {
		return this.fieldName = fieldName;
	}

	public Value getValueName() {
		return valueName;
	}

	public Value setValueName(Value valueName) {
		return this.valueName = valueName;
	}
}
