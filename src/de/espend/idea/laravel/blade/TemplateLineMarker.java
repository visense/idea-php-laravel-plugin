package de.espend.idea.laravel.blade;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.navigation.GotoRelatedItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.ConstantFunction;
import com.intellij.util.indexing.FileBasedIndexImpl;
import com.intellij.util.indexing.ID;
import com.jetbrains.php.PhpIcons;
import com.jetbrains.php.blade.BladeFileType;
import com.jetbrains.php.blade.psi.BladePsiDirectiveParameter;
import com.jetbrains.php.blade.psi.BladeTokenTypes;
import de.espend.idea.laravel.LaravelIcons;
import de.espend.idea.laravel.LaravelProjectComponent;
import de.espend.idea.laravel.blade.util.BladePsiUtil;
import de.espend.idea.laravel.blade.util.BladeTemplateUtil;
import de.espend.idea.laravel.stub.BladeExtendsStubIndex;
import de.espend.idea.laravel.stub.BladeIncludeStubIndex;
import de.espend.idea.laravel.stub.BladeSectionStubIndex;
import de.espend.idea.laravel.stub.PhpTemplateUsageStubIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class TemplateLineMarker implements LineMarkerProvider {

    @Nullable
    @Override
    public LineMarkerInfo getLineMarkerInfo(@NotNull PsiElement psiElement) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<PsiElement> psiElements, @NotNull Collection<LineMarkerInfo> collection) {

        // we need project element; so get it from first item
        if(psiElements.size() == 0) {
            return;
        }

        Project project = psiElements.get(0).getProject();
        if(!LaravelProjectComponent.isEnabled(project)) {
            return;
        }

        for(PsiElement psiElement: psiElements) {

            if(psiElement instanceof PsiFile) {
                collectTemplateFileRelatedFiles((PsiFile) psiElement, collection);
            }

            if(psiElement.getNode().getElementType() == BladeTokenTypes.SECTION_DIRECTIVE) {
                PsiElement nextSibling = psiElement.getNextSibling();
                if(nextSibling instanceof BladePsiDirectiveParameter) {
                    String sectionName = BladePsiUtil.getSection(nextSibling);
                    if(sectionName != null) {
                        collectOverwrittenSection(nextSibling, collection, sectionName);
                        collectImplementsSection(nextSibling, collection, sectionName);
                    }
                }
            }

            if(psiElement.getNode().getElementType() == BladeTokenTypes.YIELD_DIRECTIVE) {
                PsiElement nextSibling = psiElement.getNextSibling();
                if(nextSibling instanceof BladePsiDirectiveParameter) {
                    String sectionName = BladePsiUtil.getSection(nextSibling);
                    if(sectionName != null) {
                        collectImplementsSection(nextSibling, collection, sectionName);
                    }
                }
            }

        }

    }

    /**
     * like this @section('sidebar')
     */
    private void collectOverwrittenSection(final PsiElement psiElement, @NotNull Collection<LineMarkerInfo> collection, final String sectionName) {

        final List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();

        for(PsiElement psiElement1 : psiElement.getContainingFile().getChildren()) {
            PsiElement extendDirective = psiElement1.getFirstChild();
            if(extendDirective != null && extendDirective.getNode().getElementType() == BladeTokenTypes.EXTENDS_DIRECTIVE) {
                PsiElement bladeParameter = extendDirective.getNextSibling();
                if(bladeParameter instanceof BladePsiDirectiveParameter) {
                    String extendTemplate = BladePsiUtil.getSection(bladeParameter);
                    if(extendTemplate != null) {
                        Set<VirtualFile> virtualFiles = BladeTemplateUtil.resolveTemplateName(psiElement.getProject(), extendTemplate);
                        for(VirtualFile virtualFile: virtualFiles) {
                            PsiFile psiFile = PsiManager.getInstance(psiElement.getProject()).findFile(virtualFile);
                            if(psiFile != null) {
                                visitOverwrittenTemplateFile(psiFile, gotoRelatedItems, sectionName);
                            }

                        }
                    }
                }
            }
        }

        if(gotoRelatedItems.size() == 0) {
            return;
        }

        collection.add(getRelatedPopover("Parent Section", "Blade Section", psiElement, gotoRelatedItems, PhpIcons.OVERRIDES));
    }

    private void collectTemplateFileRelatedFiles(final PsiFile psiFile, @NotNull Collection<LineMarkerInfo> collection) {

        Set<String> collectedTemplates = BladeTemplateUtil.getFileTemplateName(psiFile.getProject(), psiFile.getVirtualFile());
        if(collectedTemplates.size() == 0) {
            return;
        }

        // lowercase for index
        Set<String> templateNames = new HashSet<>();
        for (String templateName : collectedTemplates) {
            templateNames.add(templateName);
            templateNames.add(templateName.toLowerCase());
        }

        // normalize all template names and support both: "foo.bar" and "foo/bar"
        templateNames.addAll(new HashSet<>(templateNames)
            .stream().map(templateName -> templateName.replace(".", "/"))
            .collect(Collectors.toList())
        );

        final List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();

        for(ID<String, Void> key : Arrays.asList(BladeExtendsStubIndex.KEY, BladeSectionStubIndex.KEY, BladeIncludeStubIndex.KEY)) {
            for(String templateName: templateNames) {
                FileBasedIndexImpl.getInstance().getFilesWithKey(key, new HashSet<>(Collections.singletonList(templateName)), virtualFile -> {
                    PsiFile psiFileTarget = PsiManager.getInstance(psiFile.getProject()).findFile(virtualFile);

                    if(psiFileTarget != null) {
                        gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(psiFileTarget).withIcon(LaravelIcons.LARAVEL, LaravelIcons.LARAVEL));
                    }

                    return true;
                }, GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.allScope(psiFile.getProject()), BladeFileType.INSTANCE, BladeFileType.INSTANCE));
            }
        }

        // collect tagged index files
        Collection<VirtualFile> files = new HashSet<>();
        for(final String templateName: templateNames) {
            files.addAll(
                FileBasedIndexImpl.getInstance().getContainingFiles(PhpTemplateUsageStubIndex.KEY, templateName, GlobalSearchScope.allScope(psiFile.getProject()))
            );
        }

        for (VirtualFile file : files) {
            PsiFile psiFileTarget = PsiManager.getInstance(psiFile.getProject()).findFile(file);
            if(psiFileTarget == null) {
                continue;
            }

            Collection<Pair<String, PsiElement>> pairs = BladeTemplateUtil.getViewTemplatesPairScope(psiFileTarget);
            for (String templateName : templateNames) {
                for (Pair<String, PsiElement> pair : pairs) {
                    if(templateName.equalsIgnoreCase(pair.first)) {
                        gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(pair.getSecond()).withIcon(PhpIcons.IMPLEMENTED, PhpIcons.IMPLEMENTED));
                    }
                }
            }
        }

        if(gotoRelatedItems.size() == 0) {
            return;
        }

        collection.add(getRelatedPopover("Template", "Blade File", psiFile, gotoRelatedItems, PhpIcons.IMPLEMENTED));
    }

    private LineMarkerInfo getRelatedPopover(String singleItemTitle, String singleItemTooltipPrefix, PsiElement lineMarkerTarget, List<GotoRelatedItem> gotoRelatedItems, Icon icon) {

        // single item has no popup
        String title = singleItemTitle;
        if(gotoRelatedItems.size() == 1) {
            String customName = gotoRelatedItems.get(0).getCustomName();
            if(customName != null) {
                title = String.format(singleItemTooltipPrefix, customName);
            }
        }

        return new LineMarkerInfo<>(lineMarkerTarget, lineMarkerTarget.getTextOffset(), icon, 6, new ConstantFunction<>(title), new RelatedPopupGotoLineMarker.NavigationHandler(gotoRelatedItems));
    }

    private void visitOverwrittenTemplateFile(final PsiFile psiFile, final List<GotoRelatedItem> gotoRelatedItems, final String sectionName) {
        visitOverwrittenTemplateFile(psiFile, gotoRelatedItems, sectionName, 10);
    }

    private void visitOverwrittenTemplateFile(final PsiFile psiFile, final List<GotoRelatedItem> gotoRelatedItems, final String sectionName, int depth) {
        // simple secure recursive calls
        if(depth-- <= 0) {
            return;
        }

        BladeTemplateUtil.DirectiveParameterVisitor visitor = parameter -> {
            if (sectionName.equalsIgnoreCase(parameter.getContent())) {
                gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(parameter.getPsiElement()).withIcon(LaravelIcons.LARAVEL, LaravelIcons.LARAVEL));
            }
        };

        BladeTemplateUtil.visitSection(psiFile, visitor);
        BladeTemplateUtil.visitYield(psiFile, visitor);

        final int finalDepth = depth;
        BladeTemplateUtil.visitExtends(psiFile, parameter -> {
            Set<VirtualFile> virtualFiles = BladeTemplateUtil.resolveTemplateName(psiFile.getProject(), parameter.getContent());
            for (VirtualFile virtualFile : virtualFiles) {
                PsiFile templatePsiFile = PsiManager.getInstance(psiFile.getProject()).findFile(virtualFile);
                if (templatePsiFile != null) {
                    visitOverwrittenTemplateFile(templatePsiFile, gotoRelatedItems, sectionName, finalDepth);
                }
            }
        });

    }


    /**
     * Find all sub implementations of a section that are overwritten by an extends tag
     * Possible targets are: @section('sidebar')
     */
    private void collectImplementsSection(PsiElement psiElement, @NotNull Collection<LineMarkerInfo> collection, final String sectionName) {

        Set<String> templateNames = BladeTemplateUtil.getFileTemplateName(psiElement.getProject(), psiElement.getContainingFile().getVirtualFile());
        if(templateNames.size() == 0) {
            return;
        }

        final List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();

        Set<VirtualFile> virtualFiles = BladeTemplateUtil.getExtendsImplementations(psiElement.getProject(), templateNames);
        if(virtualFiles.size() == 0) {
            return;
        }

        for(VirtualFile virtualFile: virtualFiles) {
            PsiFile psiFile = PsiManager.getInstance(psiElement.getProject()).findFile(virtualFile);
            if(psiFile != null) {
                BladeTemplateUtil.visitSection(psiFile, parameter -> {
                    if (sectionName.equalsIgnoreCase(parameter.getContent())) {
                        gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(parameter.getPsiElement()).withIcon(LaravelIcons.LARAVEL, LaravelIcons.LARAVEL));
                    }
                });
            }

        }

        if(gotoRelatedItems.size() == 0) {
            return;
        }

        collection.add(getRelatedPopover("Template", "Blade File", psiElement, gotoRelatedItems, PhpIcons.IMPLEMENTED));
    }

    private void collectYieldImplementsSection(final PsiElement psiElement, @NotNull Collection<LineMarkerInfo> collection, final String sectionName) {

        Set<String> templateNames = BladeTemplateUtil.getFileTemplateName(psiElement.getProject(), psiElement.getContainingFile().getVirtualFile());
        if(templateNames.size() == 0) {
            return;
        }

        final List<GotoRelatedItem> gotoRelatedItems = new ArrayList<>();

        Set<VirtualFile> virtualFiles = BladeTemplateUtil.getExtendsImplementations(psiElement.getProject(), templateNames);
        if(virtualFiles.size() == 0) {
            return;
        }

        for(VirtualFile virtualFile: virtualFiles) {
            PsiFile psiFile = PsiManager.getInstance(psiElement.getProject()).findFile(virtualFile);
            if(psiFile != null) {
                BladeTemplateUtil.visitYield(psiFile, parameter -> {
                    if (sectionName.equalsIgnoreCase(parameter.getContent())) {
                        gotoRelatedItems.add(new RelatedPopupGotoLineMarker.PopupGotoRelatedItem(parameter.getPsiElement()).withIcon(LaravelIcons.LARAVEL, LaravelIcons.LARAVEL));
                    }
                });
            }

        }

        if(gotoRelatedItems.size() == 0) {
            return;
        }

        collection.add(getRelatedPopover("Template", "Blade File", psiElement, gotoRelatedItems, PhpIcons.IMPLEMENTED));
    }

}
