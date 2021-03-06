package io.fisache.watchgithub.ui.repositorieslist;

import android.content.res.Resources;

import java.util.List;

import io.fisache.watchgithub.R;
import io.fisache.watchgithub.base.BasePresenter;
import io.fisache.watchgithub.data.manager.CacheRepositoriesManager;
import io.fisache.watchgithub.data.manager.GithubRepositoriesManager;
import io.fisache.watchgithub.data.model.Repository;
import io.fisache.watchgithub.data.model.User;
import io.fisache.watchgithub.service.SchedulerProvider;
import io.fisache.watchgithub.util.DateUtils;
import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class RepositoriesListActivityPresenter implements BasePresenter {
    private RepositoriesListActivity activity;
    private User user;
    private GithubRepositoriesManager githubRepositoriesManager;
    private CacheRepositoriesManager cacheRepositoriesManager;
    private CompositeSubscription subscription;

    private Resources resources;

    private RepoFilterType repoFilterType = RepoFilterType.ALL;

    private int repoPage = 1;

    private boolean dataMore = true;

    private boolean cached = false;

    private SchedulerProvider schedulerProvider = SchedulerProvider.getInstance();

    public RepositoriesListActivityPresenter(RepositoriesListActivity activity, User user,
                                             GithubRepositoriesManager githubRepositoriesManager,
                                             CacheRepositoriesManager cacheRepositoriesManager) {
        this.activity = activity;
        this.user = user;
        this.githubRepositoriesManager = githubRepositoriesManager;
        this.cacheRepositoriesManager = cacheRepositoriesManager;
        subscription = new CompositeSubscription();
        resources = activity.getResources();
    }

    @Override
    public void subscribe() {
        if(subscription == null) {
            subscription = new CompositeSubscription();
        }
        repoPage = cacheRepositoriesManager.getCachedRepoPage(user.login);
        setRepositories();
    }

    @Override
    public void unsubscribe() {
        subscription = null;
    }

    public void setRepositories() {
        if(!dataMore) {
            return ;
        }
        activity.showLoading(true);
        subscription.clear();
        Subscription mSubscription = cacheRepositoriesManager.getUserRepositories(repoPage)
                .subscribe(new Observer<List<Repository>>() {
                    @Override
                    public void onCompleted() {
                        activity.showLoading(false);
                    }

                    @Override
                    public void onError(Throwable e) {
                        // cache miss
                        githubRepositoriesManager.getUserRepositories(repoPage)
//                                .subscribeOn(Schedulers.io())
//                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeOn(schedulerProvider.io())
                                .observeOn(schedulerProvider.ui())
                                .subscribe(new Observer<List<Repository>>() {
                                    @Override
                                    public void onCompleted() {
                                        activity.showLoading(false);
                                    }

                                    @Override
                                    public void onError(Throwable e) {
                                        activity.showLoading(false);
                                    }

                                    @Override
                                    public void onNext(List<Repository> repositories) {
                                        cacheRepositoriesManager.replaceCache(repositories, repoPage);
                                        processRepositories(repositories);
                                    }
                                });
                    }

                    @Override
                    public void onNext(List<Repository> repositories) {
                        // cache hit
                        processRepositories(repositories);
                    }
                });
        subscription.add(mSubscription);
    }

    private void processRepositories(List<Repository> repositories) {
        // first data empty
        if(repositories.isEmpty() && OnRepoScrollListener.repoScrollLoading) {
            activity.showNotExistRepositories();
            return ;
        }

        // first data exist
        if(!repositories.isEmpty() && OnRepoScrollListener.repoScrollLoading) {
            activity.showExistRepositories();
            activity.setRepositories(repositories, true, false);
            return ;
        }

        //scroll

        // next data empty
        if(repositories.isEmpty()) {
            // next data not exist, so not setRepsitories call
            dataMore = false;
            activity.showNotMoreData();
        } else {
            dataMore = true;
            activity.setRepositories(repositories, false, false);
        }
        OnRepoScrollListener.repoScrollLoading = true;


    }

    public void setFilter(RepoFilterType type) {
        repoFilterType = type;
    }

    public void setNextRepositories() {
        repoPage = cacheRepositoriesManager.getCachedRepoPage(user.login) + 1;
        setRepositories();
    }

    void setCacheRepositories() {
        cached = true;
        activity.showLoading(true);
        cacheRepositoriesManager.getUserRepositories(repoPage)
                .flatMap(new Func1<List<Repository>, Observable<Repository>>() {
                    @Override
                    public Observable<Repository> call(List<Repository> repositories) {
                        return Observable.from(repositories);
                    }
                })
                .filter(new Func1<Repository, Boolean>() {
                    @Override
                    public Boolean call(Repository repository) {
                        switch (repoFilterType) {
                            case POPULAR:
                                return repository.star_count > resources.getInteger(R.integer.repo_popular_star) ? true : false;
                            case ORIGIN:
                                return !repository.fork;
                            case FORKED:
                                return repository.fork;
                            case RECENTLY:
                                return DateUtils.getTermsFromLastPushed(repository.pushed_at) <= resources.getInteger(R.integer.repo_recent_push_terms) ? true : false;
                            case ALL:
                                cached = false;
                            default:
                                return true;
                        }
                    }
                })
                .toList()
                .subscribe(new Observer<List<Repository>>() {
                    @Override
                    public void onCompleted() {
                        activity.showLoading(false);
                    }

                    @Override
                    public void onError(Throwable e) {
                        activity.showLoading(false);
                    }

                    @Override
                    public void onNext(List<Repository> repositories) {
                        activity.setRepositories(repositories, false, cached);
                    }
                });
    }

    public boolean isCached() {
        return cached;
    }
}
