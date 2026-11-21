package com.kachur.vpn

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IpFeedUpdateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val a = checkNotNull(IpUpdateSource.custom("https://a.example.test/ip.json"))
    private val b = checkNotNull(IpUpdateSource.custom("https://b.example.test/ip.json"))

    @Test
    fun combinesBothJsonFormatsAndKeepsManualRulesAcrossRefreshAndRemoval() {
        val routing = SplitTunnelingSettings(context)
        val feeds = IpUpdateSettings(context)
        val manual = "192.0.2.0/24"
        routing.addIpRoutes(setOf(manual))
        feeds.change(false, listOf(a, b), IpUpdateSchedule.WEEKLY)
        val first = IpFeedUpdater(context, { source ->
            if (source == a) parse("""[{"hostname":"198.51.100.9/24","ip":""}]""")
            else parse("""{"version":1,"bypass":["198.51.100.0/24","203.0.113.1"]}""")
        })
        assertEquals(IpUpdateResult.SUCCESS, first.refresh())
        assertEquals(setOf(manual, "198.51.100.0/24", "203.0.113.1/32"), routing.ipRoutes())
        assertTrue(feeds.timeFor(a) > 0L)
        assertEquals(feeds.timeFor(a), feeds.timeFor(b))

        val second = IpFeedUpdater(context, { if (it == a) setOf("198.51.100.7/32") else setOf("203.0.113.1/32") })
        assertEquals(IpUpdateResult.SUCCESS, second.refresh())
        assertEquals(setOf(manual, "198.51.100.7/32", "203.0.113.1/32"), routing.ipRoutes())
        feeds.change(false, listOf(a), IpUpdateSchedule.MANUAL)
        assertEquals(setOf(manual, "198.51.100.7/32"), routing.ipRoutes())
        assertEquals(setOf(manual), routing.manualIpRoutes())
        assertEquals(0L, feeds.timeFor(b))
    }

    @Test
    fun failedDownloadAndInvalidDocumentsLeaveAllLastGoodSnapshotsIntact() {
        val feeds = IpUpdateSettings(context)
        feeds.change(false, listOf(a, b), IpUpdateSchedule.WEEKLY)
        assertEquals(IpUpdateResult.SUCCESS, IpFeedUpdater(context, { setOf("192.0.2.1/32") }).refresh())
        val earlier = feeds.timeFor(a)
        val badNetwork = IpFeedUpdater(context, {
            if (it == a) setOf("198.51.100.12/32") else throw IOException("synthetic-sensitive-query")
        })
        assertEquals(IpUpdateResult.NETWORK, badNetwork.refresh())
        assertEquals(b.key, feeds.failedSource())
        val badJson = IpFeedUpdater(context, { parse("""{"version":1,"bypass":["invalid"]}""") })
        assertEquals(IpUpdateResult.INVALID, badJson.refresh())
        assertEquals(setOf("192.0.2.1/32"), feeds.routesFor(a))
        assertEquals(setOf("192.0.2.1/32"), feeds.routesFor(b))
        assertEquals(earlier, feeds.timeFor(a))
        assertFalse(feeds.preferences.all.toString().contains("synthetic-sensitive-query"))
    }

    @Test
    fun rejectsRouteOverflowAndACombinedDefaultRouteAtomically() {
        val feeds = IpUpdateSettings(context)
        feeds.change(false, listOf(a, b), IpUpdateSchedule.WEEKLY)
        assertEquals(IpUpdateResult.SUCCESS, IpFeedUpdater(context, { setOf("192.0.2.1/32") }).refresh())
        val tooMany = (0 until IpRoutePlan.MAX_ROUTES).map { "198.18.${it / 128}.${(it % 128) * 2}/32" }.toSet()
        assertEquals(IpUpdateResult.LIMIT, IpFeedUpdater(context, { tooMany }).refresh())
        val all = IpFeedUpdater(context, { if (it == a) setOf("0.0.0.0/1") else setOf("128.0.0.0/1") })
        assertEquals(IpUpdateResult.UNSAFE, all.refresh())
        assertEquals(setOf("192.0.2.1/32"), SplitTunnelingSettings(context).ipRoutes())
    }

    @Test
    fun discardsDownloadIfSourcesChangeOrJobIsCancelledDuringRefresh() {
        val feeds = IpUpdateSettings(context)
        feeds.change(false, listOf(a), IpUpdateSchedule.WEEKLY)
        val stale = IpFeedUpdater(context, {
            feeds.change(false, listOf(b), IpUpdateSchedule.WEEKLY)
            setOf("192.0.2.1/32")
        })
        assertEquals(IpUpdateResult.CHANGED, stale.refresh())
        assertTrue(feeds.routesFor(a).isEmpty())
        var cancelled = false
        val worker = IpFeedUpdater(context, { cancelled = true; setOf("198.51.100.0/24") })
        assertNull(worker.refresh { cancelled })
        assertTrue(SplitTunnelingSettings(context).ipRoutes().isEmpty())
    }

    @Test
    fun registryAggregatesAddressFamiliesAndKeepsBothWithinRouteBudget() {
        val v4 = (0 until 2000).joinToString(",") { "\"198.18.${it / 128}.${(it % 128) * 2}/32\"" }
        val v6 = (0 until 2000).joinToString(",") { "\"2001:db8:${(it * 2).toString(16)}::/48\"" }
        val source = """{"status":"ok","data":{"resources":{"ipv4":["192.0.2.0/25","192.0.2.128/25",$v4],"ipv6":[$v6]}}}"""
        for (native in listOf(true, false)) {
            val rules = RipeIpFeed.read(source.byteInputStream(), native)
            assertTrue("192.0.2.0/24" in rules)
            assertTrue(rules.any { ':' in it })
            assertTrue(rules.size < 4001)
            val defaults = listOf("0.0.0.0/0", "::/0").map { checkNotNull(IpRoutePrefix.parse(it)) }
            val plan = IpRoutePlan.build(defaults, rules.map { checkNotNull(IpRoutePrefix.parse(it)) }, native)
            assertTrue(plan.tunnel.size + plan.direct.size < IpRoutePlan.MAX_ROUTES)
        }
        val wrongFamily = """{"status":"ok","data":{"resources":{"ipv4":["2001:db8::/48"],"ipv6":["2001:db8::/48"]}}}"""
        assertEquals(IpUpdateResult.INVALID, assertThrows(IpFeedException::class.java) {
            RipeIpFeed.read(wrongFamily.byteInputStream(), true)
        }.result)
    }

    @Test
    fun schedulesUniqueDailyWeeklyJobsAndDisablesPeriodInManualMode() {
        val settings = IpUpdateSettings(context)
        val manager = context.getSystemService(JobScheduler::class.java)
        assertEquals(IpUpdateSchedule.WEEKLY, settings.configuration().schedule)
        assertTrue(IpUpdateJobs.schedule(context, settings.configuration()))
        assertTrue(manager.allPendingJobs.isEmpty())
        settings.change(true, emptyList(), IpUpdateSchedule.WEEKLY)
        assertTrue(IpUpdateJobs.schedule(context, settings.configuration()))
        val weekly = manager.allPendingJobs.single()
        assertEquals(TimeUnit.DAYS.toMillis(7), weekly.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, weekly.networkType)
        assertTrue(weekly.isPersisted)
        settings.change(true, emptyList(), IpUpdateSchedule.DAILY)
        assertTrue(IpUpdateJobs.schedule(context, settings.configuration()))
        assertEquals(TimeUnit.DAYS.toMillis(1), manager.allPendingJobs.single().intervalMillis)
        settings.change(true, emptyList(), IpUpdateSchedule.MANUAL)
        assertTrue(IpUpdateJobs.schedule(context, settings.configuration()))
        assertTrue(manager.allPendingJobs.isEmpty())
        assertTrue(IpUpdateJobs.updateNow(context))
        assertTrue(IpUpdateJobs.updateNow(context))
        assertFalse(manager.allPendingJobs.single().isPeriodic)
    }

    @Test
    fun acceptsHttpsWithoutLeakingQueryInTheSourceLabel() {
        assertNull(IpUpdateSource.custom("http://a.example.test/ip.json"))
        assertNull(IpUpdateSource.custom("https://user:a@a.example.test/ip.json"))
        assertNull(IpUpdateSource.custom("https://a.example.test/ip.json#secret"))
        val source = checkNotNull(IpUpdateSource.custom("https://a.example.test/a/../ip.json?token=synthetic"))
        assertEquals("a.example.test", source.title(context))
        assertTrue(source.url.toString().contains("/ip.json"))
    }

    private fun parse(value: String) = value.byteInputStream().use(IpRouteImport::read)
}
