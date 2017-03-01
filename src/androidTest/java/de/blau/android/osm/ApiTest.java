package de.blau.android.osm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.orhanobut.mockwebserverplus.MockWebServerPlus;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import de.blau.android.App;
import de.blau.android.Logic;
import de.blau.android.Main;
import de.blau.android.PostAsyncActionHandler;
import de.blau.android.R;
import de.blau.android.exception.OsmException;
import de.blau.android.exception.OsmServerException;
import de.blau.android.prefs.AdvancedPrefDatabase;
import de.blau.android.prefs.Preferences;
import de.blau.android.resources.TileLayerServer;
import de.blau.android.tasks.Note;
import de.blau.android.tasks.Task;
import de.blau.android.tasks.TransferTasks;
import okhttp3.HttpUrl;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ApiTest {
	
	MockWebServerPlus mockServer = null;
	Context context = null;
	AdvancedPrefDatabase prefDB = null;
	
    @Rule
    public ActivityTestRule<Main> mActivityRule = new ActivityTestRule<>(Main.class);

    @Before
    public void setup() {
		context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Preferences prefs = new Preferences(context);
		prefs.setBackGroundLayer(TileLayerServer.LAYER_NONE); // try to avoid downloading tiles
    	mockServer = new MockWebServerPlus();
 		HttpUrl mockBaseUrl = mockServer.server().url("/api/0.6/");
		System.out.println("mock api url " + mockBaseUrl.toString());
 		prefDB = new AdvancedPrefDatabase(context);
 		prefDB.deleteAPI("Test");
		prefDB.addAPI("Test", "Test", mockBaseUrl.toString(), null, null, "user", "pass", null, false);
 		prefDB.selectAPI("Test");
    }
    
    @After
    public void teardown() {
		try {
			mockServer.server().shutdown();
		} catch (IOException ioex) {
			System.out.println("Stopping mock webserver exception " + ioex);
		}
    }
    
    @Test
	public void capabilities() {
    	mockServer.enqueue("capabilities1");

    	final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
    	Capabilities result = s.getCapabilities();

    	Assert.assertNotNull(result);
    	Assert.assertEquals(result.minVersion,"0.6");
    	Assert.assertEquals(result.gpxStatus, Capabilities.Status.ONLINE);
    	Assert.assertEquals(result.apiStatus, Capabilities.Status.ONLINE);
    	Assert.assertEquals(result.dbStatus, Capabilities.Status.ONLINE);
    	Assert.assertEquals(Way.maxWayNodes, 2001);		
	}
    
    @Test
	public void dataDownload() {
    	final CountDownLatch signal = new CountDownLatch(1);
    	mockServer.enqueue("capabilities1");
    	mockServer.enqueue("download1");
    	Logic logic = App.getLogic();
    	try {
			logic.downloadBox(new BoundingBox(8.3879800D,47.3892400D,8.3844600D,47.3911300D), false, new PostAsyncActionHandler() {
				@Override
				public void execute() {
					signal.countDown();
				}});
		} catch (OsmException e) {
			Assert.fail(e.getMessage());
		}
    	try {
			signal.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertNotNull(App.getDelegator().getOsmElement(Node.NAME, 101792984));
	}
    
    @Test
	public void dataDownloadMerge() {
    	dataDownload();
    	final CountDownLatch signal = new CountDownLatch(1);
    	mockServer.enqueue("capabilities1");
    	mockServer.enqueue("download2");
    	Logic logic = App.getLogic();
    	try {
			logic.downloadBox(new BoundingBox(8.3865200D,47.3883000D,8.3838500D,47.3898500D), true, new PostAsyncActionHandler() {
				@Override
				public void execute() {				
					signal.countDown();
				}});
		} catch (OsmException e) {
			Assert.fail(e.getMessage());
		}
    	try {
    		signal.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertNotNull(App.getDelegator().getOsmElement(Node.NAME, 101792984));
	}
    
    @Test
	public void dataUpload() {
    	final CountDownLatch signal = new CountDownLatch(1);
    	Logic logic = App.getLogic();

    	ClassLoader loader = Thread.currentThread().getContextClassLoader();
    	InputStream is = loader.getResourceAsStream("test1.osm");
    	logic.readOsmFile(is, false, new PostAsyncActionHandler() {
    		@Override
    		public void execute() {
    			signal.countDown();
    		}});
    	try {
    		signal.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertEquals(App.getDelegator().getApiElementCount(),32);
    	Node n = (Node) App.getDelegator().getOsmElement(Node.NAME, 101792984);
    	Assert.assertNotNull(n);
    	Assert.assertEquals(n.getState(), OsmElement.STATE_MODIFIED);
    	
    	mockServer.enqueue("capabilities1");
    	mockServer.enqueue("changeset1");
    	mockServer.enqueue("upload1");
    	mockServer.enqueue("close_changeset");
    	
 		final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
    	try {
			App.getDelegator().uploadToServer(s, "TEST", "none", true);
		} catch (OsmServerException e) {
			Assert.fail(e.getMessage());
		} catch (MalformedURLException e) {
			Assert.fail(e.getMessage());
		} catch (ProtocolException e) {
			Assert.fail(e.getMessage());
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertEquals(50000,s.getCachedCapabilities().maxElementsInChangeset);
    	n = (Node) App.getDelegator().getOsmElement(Node.NAME, 101792984);
    	Assert.assertNotNull(n);
    	Assert.assertEquals(n.getState(), OsmElement.STATE_UNCHANGED);
    	Assert.assertEquals(n.getOsmVersion(), 7);
    	Relation r = (Relation) App.getDelegator().getOsmElement(Relation.NAME,2807173);
    	Assert.assertEquals(r.getState(), OsmElement.STATE_UNCHANGED);
    	Assert.assertEquals(r.getOsmVersion(), 4);
	}
    
    @Test
	public void dataUploadSplit() {
    	final CountDownLatch signal = new CountDownLatch(1);
    	Logic logic = App.getLogic();

    	ClassLoader loader = Thread.currentThread().getContextClassLoader();
    	InputStream is = loader.getResourceAsStream("test1.osm");
    	logic.readOsmFile(is, false, new PostAsyncActionHandler() {
    		@Override
    		public void execute() {
    			signal.countDown();
    		}});
    	try {
    		signal.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertEquals(App.getDelegator().getApiElementCount(),32);
    	Node n = (Node) App.getDelegator().getOsmElement(Node.NAME, 101792984);
    	Assert.assertNotNull(n);
    	Assert.assertEquals(n.getState(), OsmElement.STATE_MODIFIED);
    	
    	mockServer.enqueue("capabilities2");
    	mockServer.enqueue("changeset2");
    	mockServer.enqueue("upload2");
    	mockServer.enqueue("close_changeset");
    	mockServer.enqueue("changeset3");
    	mockServer.enqueue("upload3");
    	mockServer.enqueue("close_changeset");
    	mockServer.enqueue("changeset4");
    	mockServer.enqueue("upload4");
    	mockServer.enqueue("close_changeset");
    	
 		final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
    	try {
			App.getDelegator().uploadToServer(s, "TEST", "none", false);
		} catch (OsmServerException e) {
			Assert.fail(e.getMessage());
		} catch (MalformedURLException e) {
			Assert.fail(e.getMessage());
		} catch (ProtocolException e) {
			Assert.fail(e.getMessage());
		} catch (IOException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertEquals(11,s.getCachedCapabilities().maxElementsInChangeset);
    	n = (Node) App.getDelegator().getOsmElement(Node.NAME, 101792984);
    	Assert.assertNotNull(n);
    	Assert.assertEquals(n.getState(), OsmElement.STATE_UNCHANGED);
    	Assert.assertEquals(n.getOsmVersion(), 7);
    	Relation r = (Relation) App.getDelegator().getOsmElement(Relation.NAME,2807173);
    	Assert.assertEquals(r.getState(), OsmElement.STATE_UNCHANGED);
    	Assert.assertEquals(r.getOsmVersion(), 4);
	}
    
    @Test
	public void dataUploadErrors() {
    	final CountDownLatch signal = new CountDownLatch(1);
    	Logic logic = App.getLogic();
    	
    	// we need something changes in memory or else we wont try to upload
       	ClassLoader loader = Thread.currentThread().getContextClassLoader();
    	InputStream is = loader.getResourceAsStream("test1.osm");
    	logic.readOsmFile(is, false, new PostAsyncActionHandler() {
    		@Override
    		public void execute() {
    			signal.countDown();
    		}});
    	try {
    		signal.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	Assert.assertTrue(App.getDelegator().getApiElementCount() > 0);
    	apiErrorTest(401);
    	apiErrorTest(403);
    	apiErrorTest(999);
	}

	private void apiErrorTest(int code) {
		mockServer.enqueue("capabilities1");
    	mockServer.enqueue("" + code);
    	
 		final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
 		s.resetChangeset();
    	try {
			App.getDelegator().uploadToServer(s, "TEST", "none", true);
		} catch (OsmServerException e) {
			System.out.println(e.getMessage());
			Assert.assertEquals(code,e.getErrorCode());
			return;
		} catch (MalformedURLException e) {
			Assert.fail(e.getMessage());
			return;
		} catch (ProtocolException e) {
			Assert.fail(e.getMessage());
			return;
		} catch (IOException e) {
			Assert.fail(e.getMessage());
			return;
		} 
		Assert.fail("Expected error " + code);
	}
    
    @Test
	public void notesDownload() {
    	final CountDownLatch signal = new CountDownLatch(1);
    	// mockServer.enqueue("capabilities1");
    	mockServer.enqueue("notesDownload1");
    	App.getTaskStorage().reset();
    	try {
    		final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
    		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
    		Resources r = context.getResources();
    		Set<String> set = new HashSet<String>(Arrays.asList("NOTES")) ;
			p.edit().putStringSet(r.getString(R.string.config_bugFilter_key), set).commit();
    		Assert.assertTrue(new Preferences(context).taskFilter().contains("NOTES"));
			TransferTasks.downloadBox(context,s,new BoundingBox(8.3879800D,47.3892400D,8.3844600D,47.3911300D), false, new PostAsyncActionHandler() {
				@Override
				public void execute() {
					signal.countDown();
				}});
		} catch (Exception e) {
			Assert.fail(e.getMessage());
		}
    	try {
			signal.await(40, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Assert.fail(e.getMessage());
		}
    	ArrayList<Task> tasks = App.getTaskStorage().getTasks();
    	// note the fixture contains 100 notes, however 41 of them are closed and expired
    	Assert.assertEquals(59, tasks.size());
    	try {
    		tasks = App.getTaskStorage().getTasks(new BoundingBox(-0.0917, 51.532, -0.0918, 51.533));
    	} catch (Exception e){
    		Assert.fail(e.getMessage());
    	}
    	Assert.assertTrue(tasks.get(0) instanceof Note);
    	Assert.assertEquals(458427,tasks.get(0).getId());
	}
    
//    @Test
//	public void noteUpload() {
//    	mockServer.enqueue("noteUpload1");
//    	App.getTaskStorage().reset();
//    	try {
//    		final Server s = new Server(context, prefDB.getCurrentAPI(),"vesupucci test");
//    		Note n = new Note((int)(51.0*1E7D),(int)(0.1*1E7D));
//    		Assert.assertTrue(n.isNew());
//    		Assert.assertTrue(TransferTasks.uploadNote(context,s, n, "ThisIsANote", false, false));
//		} catch (Exception e) {
//			Assert.fail(e.getMessage());
//		}
//    	// Assert.assertNotNull(App.getTaskStorage().getTasks()));
//    	try {
//    		// ArrayList<Task>tasks = App.getTaskStorage().getTasks(new BoundingBox(50.99, 0.099, 51.01, 0.11));
//    		ArrayList<Task> tasks = App.getTaskStorage().getTasks();
//        	Assert.assertEquals(1, tasks.size());
//        	Note n = (Note) tasks.get(0);
//        	Assert.assertEquals(n.getLastComment().getText(),"ThisIsANote");
//    	} catch (Exception e) {
//    		Assert.fail(e.getMessage());
//    	}
//	}
}
