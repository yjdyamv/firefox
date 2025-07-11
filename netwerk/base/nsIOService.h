/* -*- Mode: C++; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef nsIOService_h__
#define nsIOService_h__

#include "nsStringFwd.h"
#include "nsIIOService.h"
#include "nsTArray.h"
#include "nsCOMPtr.h"
#include "nsIObserver.h"
#include "nsIWeakReferenceUtils.h"
#include "nsILoadInfo.h"
#include "nsINetUtil.h"
#include "nsIChannelEventSink.h"
#include "nsCategoryCache.h"
#include "nsISpeculativeConnect.h"
#include "nsWeakReference.h"
#include "mozilla/Atomics.h"
#include "mozilla/Attributes.h"
#include "mozilla/RWLock.h"
#include "mozilla/net/ProtocolHandlerInfo.h"
#include "prtime.h"
#include "nsICaptivePortalService.h"
#include "nsIObserverService.h"
#include "nsTHashSet.h"
#include "nsWeakReference.h"
#include "nsNetCID.h"
#include "SimpleURIUnknownSchemes.h"

// We don't want to expose this observer topic.
// Intended internal use only for remoting offline/inline events.
// See Bug 552829
#define NS_IPC_IOSERVICE_SET_OFFLINE_TOPIC "ipc:network:set-offline"
#define NS_IPC_IOSERVICE_SET_CONNECTIVITY_TOPIC "ipc:network:set-connectivity"

class nsINetworkLinkService;
class nsIPrefBranch;
class nsIProtocolProxyService2;
class nsIProxyInfo;
class nsPISocketTransportService;
namespace mozilla {
class MemoryReportingProcess;
namespace net {
class NeckoChild;
class nsAsyncRedirectVerifyHelper;
class SocketProcessHost;
class SocketProcessMemoryReporter;
union NetAddr;

class nsIOService final : public nsIIOService,
                          public nsIObserver,
                          public nsINetUtil,
                          public nsISpeculativeConnect,
                          public nsSupportsWeakReference,
                          public nsIIOServiceInternal,
                          public nsIObserverService {
 public:
  NS_DECL_THREADSAFE_ISUPPORTS
  NS_DECL_NSIIOSERVICE
  NS_DECL_NSIOBSERVER
  NS_DECL_NSINETUTIL
  NS_DECL_NSISPECULATIVECONNECT
  NS_DECL_NSIIOSERVICEINTERNAL
  NS_DECL_NSIOBSERVERSERVICE

  // Gets the singleton instance of the IO Service, creating it as needed
  // Returns nullptr on out of memory or failure to initialize.
  static already_AddRefed<nsIOService> GetInstance();

  nsresult Init();
  nsresult NewURI(const char* aSpec, nsIURI* aBaseURI, nsIURI** result,
                  nsIProtocolHandler** hdlrResult);

  // Called by channels before a redirect happens. This notifies the global
  // redirect observers.
  nsresult AsyncOnChannelRedirect(nsIChannel* oldChan, nsIChannel* newChan,
                                  uint32_t flags,
                                  nsAsyncRedirectVerifyHelper* helper);

  bool IsOffline() { return mOffline; }
  bool InSleepMode() { return mInSleepMode; }
  PRIntervalTime LastOfflineStateChange() { return mLastOfflineStateChange; }
  PRIntervalTime LastConnectivityChange() { return mLastConnectivityChange; }
  PRIntervalTime LastNetworkLinkChange() { return mLastNetworkLinkChange; }
  bool IsNetTearingDown() {
    return mShutdown || mOfflineForProfileChange ||
           mHttpHandlerAlreadyShutingDown;
  }
  PRIntervalTime NetTearingDownStarted() { return mNetTearingDownStarted; }

  // nsHttpHandler is going to call this function to inform nsIOService that
  // network is in process of tearing down. Moving nsHttpConnectionMgr::Shutdown
  // to nsIOService caused problems (bug 1242755) so we doing it in this way. As
  // soon as nsIOService gets notification that it is shutdown it is going to
  // reset mHttpHandlerAlreadyShutingDown.
  void SetHttpHandlerAlreadyShutingDown();

  bool IsLinkUp();

  // Converts an internal URI (e.g. one that has a username and password in
  // it) into one which we can expose to the user, for example on the URL bar.
  static already_AddRefed<nsIURI> CreateExposableURI(nsIURI*);

  // Used to count the total number of HTTP requests made
  void IncrementRequestNumber() { mTotalRequests++; }
  uint32_t GetTotalRequestNumber() { return mTotalRequests; }
  // Used to keep "race cache with network" stats
  void IncrementCacheWonRequestNumber() { mCacheWon++; }
  uint32_t GetCacheWonRequestNumber() { return mCacheWon; }
  void IncrementNetWonRequestNumber() { mNetWon++; }
  uint32_t GetNetWonRequestNumber() { return mNetWon; }

  // Used to trigger a recheck of the captive portal status
  nsresult RecheckCaptivePortal();

  void OnProcessLaunchComplete(SocketProcessHost* aHost, bool aSucceeded);
  void OnProcessUnexpectedShutdown(SocketProcessHost* aHost);
  bool SocketProcessReady();
  static void NotifySocketProcessPrefsChanged(const char* aName, void* aSelf);
  void NotifySocketProcessPrefsChanged(const char* aName);
  static bool UseSocketProcess(bool aCheckAgain = false);

  bool IsSocketProcessLaunchComplete();

  // Call func immediately if socket process is launched completely. Otherwise,
  // |func| will be queued and then executed in the *main thread* once socket
  // process is launced.
  void CallOrWaitForSocketProcess(const std::function<void()>& aFunc);

  int32_t SocketProcessPid();
  SocketProcessHost* SocketProcess() { return mSocketProcess; }

  friend SocketProcessMemoryReporter;
  RefPtr<MemoryReportingProcess> GetSocketProcessMemoryReporter();

  // Lookup the ProtocolHandlerInfo based on a given scheme.
  // Safe to call from any thread.
  ProtocolHandlerInfo LookupProtocolHandler(const nsACString& aScheme);

  static void OnTLSPrefChange(const char* aPref, void* aSelf);

  nsresult LaunchSocketProcess();

  static bool TooManySocketProcessCrash();
  static void IncreaseSocketProcessCrashCount();
#ifdef MOZ_WIDGET_ANDROID
  static bool ShouldAddAdditionalSearchHeaders(nsIURI* aURI, bool* val);
#endif

  // Returns true if this is an essential domain and a fallback domain
  // mapping exists.
  bool GetFallbackDomain(const nsACString& aDomain,
                         nsACString& aFallbackDomain);

  NS_IMETHODIMP GetOverridenIpAddressSpace(
      nsILoadInfo::IPAddressSpace* aIpAddressSpace, const NetAddr& aAddr);

 private:
  // These shouldn't be called directly:
  // - construct using GetInstance
  // - destroy using Release
  nsIOService();
  ~nsIOService();
  nsresult SetConnectivityInternal(bool aConnectivity);

  nsresult OnNetworkLinkEvent(const char* data);

  nsresult InitializeCaptivePortalService();
  nsresult RecheckCaptivePortalIfLocalRedirect(nsIChannel* newChan);

  // Prefs wrangling
  static void PrefsChanged(const char* pref, void* self);
  void PrefsChanged(const char* pref = nullptr);
  void ParsePortList(const char* pref, bool remove);

  nsresult InitializeSocketTransportService();
  nsresult InitializeNetworkLinkService();
  nsresult InitializeProtocolProxyService();

  // consolidated helper function
  void LookupProxyInfo(nsIURI* aURI, nsIURI* aProxyURI, uint32_t aProxyFlags,
                       nsCString* aScheme, nsIProxyInfo** outPI);

  nsresult NewChannelFromURIWithProxyFlagsInternal(
      nsIURI* aURI, nsIURI* aProxyURI, uint32_t aProxyFlags,
      nsINode* aLoadingNode, nsIPrincipal* aLoadingPrincipal,
      nsIPrincipal* aTriggeringPrincipal,
      const mozilla::Maybe<mozilla::dom::ClientInfo>& aLoadingClientInfo,
      const mozilla::Maybe<mozilla::dom::ServiceWorkerDescriptor>& aController,
      uint32_t aSecurityFlags, nsContentPolicyType aContentPolicyType,
      uint32_t aSandboxFlags, nsIChannel** result);

  nsresult NewChannelFromURIWithProxyFlagsInternal(nsIURI* aURI,
                                                   nsIURI* aProxyURI,
                                                   uint32_t aProxyFlags,
                                                   nsILoadInfo* aLoadInfo,
                                                   nsIChannel** result);

  nsresult SpeculativeConnectInternal(
      nsIURI* aURI, nsIPrincipal* aPrincipal,
      Maybe<OriginAttributes>&& aOriginAttributes,
      nsIInterfaceRequestor* aCallbacks, bool aAnonymous);

  void DestroySocketProcess();

  nsresult SetOfflineInternal(bool offline, bool notifySocketProcess = true);

  bool UsesExternalProtocolHandler(const nsACString& aScheme)
      MOZ_REQUIRES_SHARED(mLock);

  void UpdateAddressSpaceOverrideList(const char* aPrefName,
                                      nsTArray<nsCString>& aTargetList);

 private:
  mozilla::Atomic<bool, mozilla::Relaxed> mOffline{true};
  mozilla::Atomic<bool, mozilla::Relaxed> mOfflineForProfileChange{false};
  bool mManageLinkStatus{false};
  mozilla::Atomic<bool, mozilla::Relaxed> mConnectivity{true};

  // Used to handle SetOffline() reentrancy.  See the comment in
  // SetOffline() for more details.
  bool mSettingOffline{false};
  bool mSetOfflineValue{false};

  bool mSocketProcessLaunchComplete{false};

  mozilla::Atomic<bool, mozilla::Relaxed> mShutdown{false};
  mozilla::Atomic<bool, mozilla::Relaxed> mHttpHandlerAlreadyShutingDown{false};
  mozilla::Atomic<bool, mozilla::Relaxed> mInSleepMode{false};

  nsCOMPtr<nsPISocketTransportService> mSocketTransportService;
  nsCOMPtr<nsICaptivePortalService> mCaptivePortalService;
  nsCOMPtr<nsINetworkLinkService> mNetworkLinkService;
  bool mNetworkLinkServiceInitialized{false};

  // cached categories
  nsCategoryCache<nsIChannelEventSink> mChannelEventSinks{
      NS_CHANNEL_EVENT_SINK_CATEGORY};

  RWLock mLock{"nsIOService::mLock"};
  nsTArray<int32_t> mRestrictedPortList MOZ_GUARDED_BY(mLock);
  nsTArray<nsCString> mForceExternalSchemes MOZ_GUARDED_BY(mLock);

  nsTArray<nsCString> mPublicAddressSpaceOverridesList MOZ_GUARDED_BY(mLock);
  nsTArray<nsCString> mPrivateAddressSpaceOverridesList MOZ_GUARDED_BY(mLock);
  nsTArray<nsCString> mLocalAddressSpaceOverrideList MOZ_GUARDED_BY(mLock);

  nsTHashMap<nsCString, RuntimeProtocolHandler> mRuntimeProtocolHandlers
      MOZ_GUARDED_BY(mLock);

  uint32_t mTotalRequests{0};
  uint32_t mCacheWon{0};
  uint32_t mNetWon{0};
  static uint32_t sSocketProcessCrashedCount;

  // These timestamps are needed for collecting telemetry on PR_Connect,
  // PR_ConnectContinue and PR_Close blocking time.  If we spend very long
  // time in any of these functions we want to know if and what network
  // change has happened shortly before.
  mozilla::Atomic<PRIntervalTime> mLastOfflineStateChange;
  mozilla::Atomic<PRIntervalTime> mLastConnectivityChange;
  mozilla::Atomic<PRIntervalTime> mLastNetworkLinkChange;

  // Time a network tearing down started.
  mozilla::Atomic<PRIntervalTime> mNetTearingDownStarted{0};

  SocketProcessHost* mSocketProcess{nullptr};

  // Events should be executed after the socket process is launched. Will
  // dispatch these events while socket process fires OnProcessLaunchComplete.
  // Note: this array is accessed only on the main thread.
  nsTArray<std::function<void()>> mPendingEvents;

  // The observer notifications need to be forwarded to socket process.
  nsTHashSet<nsCString> mObserverTopicForSocketProcess;
  // Some noticications (e.g., NS_XPCOM_SHUTDOWN_OBSERVER_ID) are triggered in
  // socket process, so we should not send the notifications again.
  nsTHashSet<nsCString> mSocketProcessTopicBlockedList;
  // Used to store the topics that are already observed by IOService.
  nsTHashSet<nsCString> mIOServiceTopicList;

  nsCOMPtr<nsIObserverService> mObserverService;

  SimpleURIUnknownSchemes mSimpleURIUnknownSchemes;

  // Maps essential domains to a fallback domain that can be used
  // to retry that request when it fails.
  // Only accessible via main thread.
  nsTHashMap<nsCStringHashKey, nsCString> mEssentialDomainMapping;

 public:
  // Used for all default buffer sizes that necko allocates.
  static uint32_t gDefaultSegmentSize;
  static uint32_t gDefaultSegmentCount;
};

/**
 * Reference to the IO service singleton. May be null.
 */
extern nsIOService* gIOService;

}  // namespace net
}  // namespace mozilla

#endif  // nsIOService_h__
