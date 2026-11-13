package dev.diorama.compat;

/** Run after ClientOnly.class has been removed, like the dedicated-server distribution. */
public final class OptionalCallTest {
	public static final class ClientOnly {}
	public static final class Dependency {
		public static String safe(String value) { return value; }
		public static ClientOnly client(ClientOnly value) { return value; }
		public String virtual(String value) {return value;}
	}
	public static void main(String[] args) throws Exception {
		Class<?> dependency = Class.forName("dev.diorama.compat.OptionalCallTest$Dependency");
		var safe = OptionalCall.statik(dependency,"safe",String.class,String.class);
		if(!safe.invoke(null,"server").equals("server"))throw new AssertionError("server call");
		var virtual=OptionalCall.virtual(dependency,"virtual",String.class,String.class);
		if(!virtual.invoke(new Dependency(),"pose").equals("pose"))throw new AssertionError("virtual call");
		boolean reflectionFailed=false;
		try {dependency.getMethods();}catch(NoClassDefFoundError expected){reflectionFailed=true;}
		if(!reflectionFailed)throw new AssertionError("Test must exclude client-only signatures");
		System.out.println("Optional vehicle API calls work without client-only overload classes");
	}
}
