package com.cimphony.cimtoole.wizards;

import static au.com.langdale.ui.builder.Templates.CheckboxTableViewer;
import static au.com.langdale.ui.builder.Templates.Field;
import static au.com.langdale.ui.builder.Templates.Grid;
import static au.com.langdale.ui.builder.Templates.Group;
import static au.com.langdale.ui.builder.Templates.Label;
import static au.com.langdale.ui.builder.Templates.RadioButton;
import static au.com.langdale.ui.builder.Templates.Row;
import static au.com.langdale.ui.builder.Templates.SaveButton;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Vector;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IExportWizard;
import org.eclipse.ui.IWorkbench;

import au.com.langdale.cimtoole.CIMToolPlugin;
import au.com.langdale.cimtoole.project.Info;
import au.com.langdale.cimtoole.wizards.SchemaExportPage;
import au.com.langdale.kena.OntModel;
import au.com.langdale.ui.builder.Template;
import au.com.langdale.ui.builder.FurnishedWizardPage.Content;
import au.com.langdale.ui.builder.Templates;
import au.com.langdale.util.Jobs;

import com.cimphony.cimtoole.CimphonyCIMToolPlugin;
import com.cimphony.cimtoole.ecore.EcoreGenerator;

public class ExportEcore extends Wizard implements IExportWizard {

	public static final String BASE_SCHEMA = "schema";
	public static final String FILE_EXT = "ecore";
	public static final String SCHEMA = BASE_SCHEMA+"."+FILE_EXT;
	
	private SchemaExportPage main = new SchemaExportPage(SCHEMA, FILE_EXT){
		
		@Override
		protected Content createContent() {
			return new Content() {

				@Override
				protected Template define() {
					return Grid(
						Group(Label("Project")), 
						Group(CheckboxTableViewer("projects")),
						Group(RadioButton("internal", "Create "+ SCHEMA + " in the project")),
						Group(RadioButton("external", "Export a file to filesystem")),
						Group(Templates.CheckBox("splitEx", "Create Separate Ecore for Root Packages")),
						Group(Label("File to export:"), Field("path"), Row(SaveButton("save", "path", "*."+FILE_EXT)))
					);
				}

				@Override
				protected void addBindings() {
					projects.bind("projects", this);
					path.bind("path", this);
				}
				
				private IProject last;
				
				@Override
				public void refresh() {
					IProject project = projects.getProject();
					if( project != null && ! project.equals(last)) {
						last = project;
						getButton("external").setSelection(project.getFile(SCHEMA).exists());
					}
					boolean external = getButton("external").getSelection();
					getButton("internal").setSelection(! external);
					getControl("path").setEnabled(external);
					getControl("save").setEnabled(external);
				}

				@Override
				public String validate() {
					internal = getButton("internal").getSelection();
					if( internal && projects.getProject().getFile(SCHEMA).exists())
						return "The file " + SCHEMA + " already exists in the project";
						
					return null;
				}
			};
		}
		
		
	};

	public void init(IWorkbench workbench, IStructuredSelection selection) {
		setWindowTitle("Export Schema"); 
		setNeedsProgressMonitor(true);
		main.setTitle(getWindowTitle());
		main.setDescription("Export the merged schema as Ecore.");
		main.setSelected(selection);
	}

	@Override
	public void addPages() {
		addPage(main);        
	}

	@Override
	public boolean performFinish() {
		URI basePath;
		boolean split = main.getContent().getButton("splitEx").getSelection();
		if( main.isInternal()){
			String projectPath = "/"+main.getProject().getName()+"/"+SCHEMA;
			basePath = URI.createPlatformResourceURI(projectPath, true);
		}else
			basePath = URI.createFileURI(main.getPathname());
		try{
		//return Jobs.runInteractive(new InternalSchemaTask(), main.getProject(), getContainer(), getShell());
			return Jobs.runInteractive(ExportEcore.exportEcoreSchema(main.getProject(), basePath, Info.getSchemaNamespace(main.getProject()), split), null, getContainer(), getShell());
		} catch (CoreException e) {
				ErrorDialog.openError(
						getShell(),
						"Error Exporting Ecore",
						e.getMessage(),
						new Status(IStatus.ERROR, CimphonyCIMToolPlugin.PLUGIN_ID, e.getMessage()));e.printStackTrace();
				return false;
		}
	}

	public static IWorkspaceRunnable exportEcoreSchema(final IProject project,	final URI basePath, final String namespace, final boolean splitEx) {
		return new IWorkspaceRunnable() {
			public void run(IProgressMonitor monitor) throws CoreException {
				IFolder folder = Info.getSchemaFolder(project);
				OntModel schema = CIMToolPlugin.getCache().getMergedOntologyWait(folder);
				
				EcoreGenerator gen = new EcoreGenerator(schema, schema, namespace, namespace, true, true, true, project, true, splitEx);
				gen.run();
				if (gen.getIndex().roots.size()<=1 && gen.getResult()!=null){
					
					EPackage ecoreModel = gen.getResult();
			        if (ecoreModel.getName() == null)
			        	ecoreModel.setName(project.getName().split("\\.")[0]);
					
					URI fileURI = basePath;
					Resource ecore = new ResourceSetImpl().createResource(fileURI);
					ecore.getContents().add(ecoreModel);
					try {
						ecore.save(Collections.EMPTY_MAP);
					} catch (IOException e) {
						Info.error("can't write to " + basePath.toString());
					}
					project.refreshLocal(1, new NullProgressMonitor());
				}else{
					ResourceSet set = new ResourceSetImpl();
					for (EPackage ecore : gen.getIndex().roots){
						String baseURI = basePath.toString().substring(0, basePath.toString().length()-(FILE_EXT.length()+1));
						URI fileURI = URI.createURI(baseURI+"-"+ecore.getName()+"."+FILE_EXT);
						Resource res = set.createResource(fileURI);
						res.getContents().add(ecore);
					}
					for (Resource res : set.getResources()){
						try {
							res.save(Collections.EMPTY_MAP);
						} catch (IOException e) {
							Info.error("can't write to " + basePath.toString());
						}
						project.refreshLocal(1, new NullProgressMonitor());
					}
					
				}
			}
		};
	}


}
