package com.sun.tools.example.trace;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.StepRequest;
import com.sun.jdi.request.ThreadDeathRequest;

/**
 * This class processes incoming JDI events and displays them
 *
 * @author Robert Field
 */
public class EventThread extends Thread {

	private final VirtualMachine vm; // Running VM
	private final String[] excludes; // Packages to exclude
	private final PrintWriter writer; // Where output goes

	static String nextBaseIndent = ""; // Starting indent for next thread

	private boolean connected = true; // Connected to VM
	private boolean vmDied = true; // VMDeath occurred

	// Maps ThreadReference to ThreadTrace instances
	private Map<ThreadReference, ThreadTrace> traceMap = new HashMap<>();
	private Map<String, Boolean> options;

	/*
	 * EventThread(VirtualMachine vm, String[] excludes, PrintWriter writer) {
	 * super("event-handler"); this.vm = vm; this.excludes = excludes;
	 * this.writer = writer; }
	 */

	public EventThread(VirtualMachine vm, PrintWriter writer,
			String[] excludes, Map<String, Boolean> options) {
		super("event-handler");
		this.vm = vm;
		this.writer = writer;
		this.excludes = excludes;
		this.options = Collections
				.unmodifiableMap(new HashMap<String, Boolean>(options));
		// TODO 自動生成されたコンストラクター・スタブ
	}

	/**
	 * Run the event handling thread. As long as we are connected, get event
	 * sets off the queue and dispatch the events within them.
	 */
	@Override
	public void run() {
		EventQueue queue = vm.eventQueue();
		while (connected) {
			try {
				EventSet eventSet = queue.remove();
				EventIterator it = eventSet.eventIterator();
				while (it.hasNext()) {
					handleEvent(it.nextEvent());
				}
				eventSet.resume();
			} catch (InterruptedException exc) {
				// Ignore
			} catch (VMDisconnectedException discExc) {
				handleDisconnectedException();
				break;
			}
		}
	}

	/**
	 * Create the desired event requests, and enable them so that we will get
	 * events.
	 *
	 * @param excludes
	 *            Class patterns for which we don't want events
	 * @param watchFields
	 *            Do we want to watch assignments to fields
	 */
	void setEventRequests(/* boolean watchFields */) {
		EventRequestManager mgr = vm.eventRequestManager();

		// want all exceptions
		ExceptionRequest excReq = mgr.createExceptionRequest(null, true, true);
		// suspend so we can step
		excReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
		excReq.enable();

		MethodEntryRequest menr = mgr.createMethodEntryRequest();
		for (int i = 0; i < excludes.length; ++i) {
			menr.addClassExclusionFilter(excludes[i]);
		}
		menr.setSuspendPolicy(EventRequest.SUSPEND_NONE);
		menr.enable();

		MethodExitRequest mexr = mgr.createMethodExitRequest();
		for (int i = 0; i < excludes.length; ++i) {
			mexr.addClassExclusionFilter(excludes[i]);
		}
		mexr.setSuspendPolicy(EventRequest.SUSPEND_NONE);
		mexr.enable();

		ThreadDeathRequest tdr = mgr.createThreadDeathRequest();
		// Make sure we sync on thread death
		tdr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
		tdr.enable();

		if (options.get("watchFields") || options.get("breakAtLines")) {
			ClassPrepareRequest cpr = mgr.createClassPrepareRequest();
			for (int i = 0; i < excludes.length; ++i) {
				cpr.addClassExclusionFilter(excludes[i]);
			}
			cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			cpr.enable();
		}
	}

	/**
	 * This class keeps context on events in one thread. In this implementation,
	 * context is the indentation prefix.
	 */
	class ThreadTrace {
		final ThreadReference thread;
		final String baseIndent;
		static final String threadDelta = "                     ";
		StringBuffer indent;

		// final String prefix;

		ThreadTrace(ThreadReference thread) {
			this.thread = thread;
			this.baseIndent = nextBaseIndent;
			indent = new StringBuffer(baseIndent);
			nextBaseIndent += threadDelta;

			/*
			 * indent.append("/"); indent.append(thread.name());
			 * indent.append("/"); prefix = indent.toString();
			 */
			println("====== " + thread.name() + " ======");
		}

		private void println(String str) {
			writer.print(indent);
			writer.println(str);

			// System.out.println("System.out.print : " + str.toString());

			/*
			 * try{ Process process = Runtime.getRuntime().exec("-fields");
			 *
			 * String text; try(InputStream in = process.getInputStream();
			 * InputStreamReader isr = new InputStreamReader(in, "UTF-8");
			 * BufferedReader reader = new BufferedReader(isr)) { StringBuilder
			 * builder = new StringBuilder(); int c; while ((c = reader.read())
			 * != -1) { builder.append((char)c); } text = builder.toString(); }
			 *
			 * int ret = process.waitFor();
			 *
			 * // System.out.println("Command Text:"); System.out.println(text);
			 * //System.out.println("Command Return Code = " + ret); } catch
			 * (IOException | InterruptedException e) { e.printStackTrace(); }
			 */

		}

		void methodEntryEvent(MethodEntryEvent event) {
			println(event.method().name() + "  --  "
					+ event.method().declaringType().name());
			indent.append("| ");
		}

		void methodExitEvent(MethodExitEvent event) {
			indent.setLength(indent.length() - 2);
		}

		void fieldWatchEvent(ModificationWatchpointEvent event) {
			Field field = event.field();
			Value value = event.valueToBe();
			println("    " + field.name() + " = " + value);
		}

		void exceptionEvent(ExceptionEvent event) {
			println("Exception: " + event.exception() + " catch: "
					+ event.catchLocation());

			// Step to the catch
			EventRequestManager mgr = vm.eventRequestManager();
			StepRequest req = mgr.createStepRequest(thread,
					StepRequest.STEP_MIN, StepRequest.STEP_INTO);
			req.addCountFilter(1); // next step only
			req.setSuspendPolicy(EventRequest.SUSPEND_ALL);
			req.enable();
		}

		// Step to exception catch
		void stepEvent(StepEvent event) {
			// Adjust call depth
			int cnt = 0;
			indent = new StringBuffer(baseIndent);
			try {
				cnt = thread.frameCount();
			} catch (IncompatibleThreadStateException exc) {
			}
			while (cnt-- > 0) {
				indent.append("| ");
			}

			EventRequestManager mgr = vm.eventRequestManager();
			mgr.deleteEventRequest(event.request());
		}

		void threadDeathEvent(ThreadDeathEvent event) {
			indent = new StringBuffer(baseIndent);
			println("====== " + thread.name() + " end ======");
		}
	}

	/**
	 * Returns the ThreadTrace instance for the specified thread, creating one
	 * if needed.
	 */
	ThreadTrace threadTrace(ThreadReference thread) {
		ThreadTrace trace = traceMap.get(thread);
		if (trace == null) {
			trace = new ThreadTrace(thread);
			traceMap.put(thread, trace);
		}
		return trace;
	}

	/**
	 * Dispatch incoming events
	 */
	private void handleEvent(Event event) {
		if (event instanceof ExceptionEvent) {
			exceptionEvent((ExceptionEvent) event);
		} else if (event instanceof ModificationWatchpointEvent) {
			fieldWatchEvent((ModificationWatchpointEvent) event);
		} else if (event instanceof MethodEntryEvent) {
			methodEntryEvent((MethodEntryEvent) event);
		} else if (event instanceof MethodExitEvent) {
			methodExitEvent((MethodExitEvent) event);
		} else if (event instanceof StepEvent) {
			stepEvent((StepEvent) event);
		} else if (event instanceof ThreadDeathEvent) {
			threadDeathEvent((ThreadDeathEvent) event);
		} else if (event instanceof ClassPrepareEvent) {
			classPrepareEvent((ClassPrepareEvent) event);
		} else if (event instanceof VMStartEvent) {
			vmStartEvent((VMStartEvent) event);
		} else if (event instanceof VMDeathEvent) {
			vmDeathEvent((VMDeathEvent) event);
		} else if (event instanceof VMDisconnectEvent) {
			vmDisconnectEvent((VMDisconnectEvent) event);
		} else {
			throw new Error("Unexpected event type");
		}
	}

	/***
	 * A VMDisconnectedException has happened while dealing with another event.
	 * We need to flush the event queue, dealing only with exit events (VMDeath,
	 * VMDisconnect) so that we terminate correctly.
	 */
	synchronized void handleDisconnectedException() {
		EventQueue queue = vm.eventQueue();
		while (connected) {
			try {
				EventSet eventSet = queue.remove();
				EventIterator iter = eventSet.eventIterator();
				while (iter.hasNext()) {
					Event event = iter.nextEvent();
					if (event instanceof VMDeathEvent) {
						vmDeathEvent((VMDeathEvent) event);
					} else if (event instanceof VMDisconnectEvent) {
						vmDisconnectEvent((VMDisconnectEvent) event);
					}
				}
				eventSet.resume(); // Resume the VM
			} catch (InterruptedException exc) {
				// ignore
			}
		}
	}

	private void vmStartEvent(VMStartEvent event) {
		writer.println("-- VM Started --");
	}

	// Forward event for thread specific processing
	private void methodEntryEvent(MethodEntryEvent event) {
		threadTrace(event.thread()).methodEntryEvent(event);

		System.out.print("\n");
		System.out.println("===========" + event.method().name()
				+ "===========");
		System.out.print(event.method().declaringType().name() + ","
				+ event.method().returnTypeName() + "," + event.method().name()
				+ event.method().argumentTypeNames() + ",");
		// クラス名,返り値の型,メソッド名,引数の型
		writeCSV("\n");
		writeCSV(event.method().declaringType().name() + ","
				+ event.method().returnTypeName() + "," + event.method().name()
				+ "," + event.method().argumentTypeNames() + ",");

		//if(event.method().argumentTypeNames().size() > 1){
			//String chageName = ",";
			//writeCSV("" +event.method().argumentTypeNames() + "");


		//}
	}

	// Forward event for thread specific processing
	private void methodExitEvent(MethodExitEvent event) {
		threadTrace(event.thread()).methodExitEvent(event);
		//writeCSV("\n");
	}

	// Forward event for thread specific processing
	private void stepEvent(StepEvent event) {
		threadTrace(event.thread()).stepEvent(event);
	}

	// Forward event for thread specific processing
	private void fieldWatchEvent(ModificationWatchpointEvent event) {
		threadTrace(event.thread()).fieldWatchEvent(event);
		//Field field = event.field();
		Value value = event.valueToBe();

		try {
			System.out.print(event.field().name() + "(" + event.field().type()
					+  ")= " + value + ", "); // 変数(変数の型)
			writeCSV(event.field().name() + "(" + event.field().type() + ")= " + value + ", ");
		} catch (ClassNotLoadedException e) {
			// TODO 自動生成された catch ブロック
			e.printStackTrace();
		}
	}

	void threadDeathEvent(ThreadDeathEvent event) {
		ThreadTrace trace = traceMap.get(event.thread());
		if (trace != null) { // only want threads we care about
			trace.threadDeathEvent(event); // Forward event
		}
	}

	/**
	 * A new class has been loaded. Set watchpoints on each of its fields
	 */
	private void classPrepareEvent(ClassPrepareEvent event) {
		EventRequestManager mgr = vm.eventRequestManager();
		List<Field> fields = event.referenceType().visibleFields();

		if (options.get("watchFields")) {
			for (Field field : event.referenceType().visibleFields()) {
				ModificationWatchpointRequest req = mgr
						.createModificationWatchpointRequest(field);
				for (String ex : excludes)
					req.addClassExclusionFilter(ex);

				/*
				 * for (Field field : fields) { /*ModificationWatchpointRequest
				 * req = mgr .createModificationWatchpointRequest(field); for
				 * (int i = 0; i < excludes.length; ++i) {
				 * req.addClassExclusionFilter(excludes[i]); }
				 */
				req.setSuspendPolicy(EventRequest.SUSPEND_NONE);
				req.enable();
			}
		}
	}

	private void exceptionEvent(ExceptionEvent event) {
		ThreadTrace trace = traceMap.get(event.thread());
		if (trace != null) { // only want threads we care about
			trace.exceptionEvent(event); // Forward event
		}
	}

	public void vmDeathEvent(VMDeathEvent event) {
		vmDied = true;
		writer.println("-- The application exited --");
	}

	public void vmDisconnectEvent(VMDisconnectEvent event) {
		connected = false;
		if (!vmDied) {
			writer.println("-- The application has been disconnected --");
		}
	}

	public void writeCSV(String name) {
		// TODO 自動生成されたメソッド・スタブ
		String dir = System.getProperty("user.dir"); // カレントディレクトリのパスを取得
		File file = new File(dir + "/test.csv"); // 出力したいファイル名を指定。Fileオブジェクトを生成
		BufferedWriter bufferedwriter = null;

		try {
			// for (int i = 0; i < name.indexOf(i); i++) {
			bufferedwriter = new BufferedWriter(new FileWriter(file, true));
			bufferedwriter.write(name);
			// bufferedwriter.write("\n");
			bufferedwriter.close();
			System.out.println("ファイルの書き込みに成功");
			// }
		} catch (IOException e) {
			System.out.println(e);

		} finally {
			if (bufferedwriter != null) {
				try {
					bufferedwriter.close();
				} catch (IOException e) {
					System.out.println(e);
				}
			}
		}

	}
}
