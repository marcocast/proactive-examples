import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

	// Retrieve env variables
	def proactive_exemple_path = args[0]
	def global_space_path = args[1]
	def workflow_catalog_url = args[2]
	def workflow_templates_dir = args[3]
	def user_name = args[4]

	def target_dir_path = ""
	def bucket = ""

	// Start by finding the next templates dir index
	def templates_dirs_list = []
	new File(workflow_templates_dir).eachDir { dir ->
		templates_dirs_list << dir.getName().toInteger()
	}
	def template_dir_name = (templates_dirs_list.sort().last() + 1) + ""
	println template_dir_name
	
	// For each example directory
	new File(proactive_exemple_path).eachDir() { dir ->  
		def metadata_file = new File(dir.absolutePath, "METADATA.json")
		if (metadata_file.exists())
		{
			println metadata_file.absolutePath	
		  
			// From json to map
			def slurper = new groovy.json.JsonSlurper()
			def metadata_file_map = (Map) slurper.parseText(metadata_file.text)
			
			def catalog_map = metadata_file_map.get("catalog")
			
			
			// DATASPACE SECTION /////////////////////////////
			
			
			if ((dataspace_map = catalog_map.get("dataspace")) != null)
			{
			  // Retrieve the targeted directory path
			  def target = dataspace_map.get("target")
			  if(target == "global")
				target_dir_path = global_space_path
				
			  // Copy all files into the targeted directory
			  dataspace_map.get("files").each { file_relative_path ->
				def file_src = new File(dir.absolutePath, file_relative_path)
				def file_src_path = file_src.absolutePath
				def file_name = file_src.getName()
				def file_dest = new File(target_dir_path, file_name)	
				def file_dest_path = file_dest.absolutePath
				Files.copy(Paths.get(file_src_path), Paths.get(file_dest_path), StandardCopyOption.REPLACE_EXISTING)
			  }
			}
			
			
			// BUCKET SECTION /////////////////////////////
			
			
			bucket = catalog_map.get("bucket")
			println "bucket: " + bucket
			
			
			// Does the bucket already exist?
			def list_buckets_cmd = [ "bash", "-c", "curl -X GET --header 'Accept: application/json' '" + workflow_catalog_url + "/buckets?owner=" + user_name + "'"]
			def response = new StringBuilder()
			list_buckets_cmd.execute().waitForProcessOutput(response, System.err)
			def bucket_id = -1
			slurper.parseText(response.toString()).get("_embedded").get("bucketMetadataList").each { bucketMetada ->
				if (bucketMetada.get("name") == bucket)
					bucket_id = bucketMetada.get("id")
					// Cannot break in a closure			
			}
			println "bucket_id after research: " + bucket_id
			
			// Create a bucket if needed
			if (bucket_id == -1)
			{
				def create_bucket_cmd = [ "bash", "-c", "curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' '" + workflow_catalog_url + "/buckets?name=" + bucket + "&owner=" + user_name + "'"]
				response = new StringBuilder()
				create_bucket_cmd.execute().waitForProcessOutput(response, System.err)
				bucket_id = slurper.parseText(response).get("id")
			}
			println "bucket_id: " + bucket_id
			
			
			// OBJECTS SECTION /////////////////////////////

			
			catalog_map.get("objects").each { object ->
				def metadata_map = object.get("metadata")
				
				
				// WORKFLOWS SECTION /////////////////////////////
				
				
				if (metadata_map.get("kind") == "workflow")
				{
					def workflow_relative_path = object.get("file")
					def workflow_file_name = new File(workflow_relative_path).getName()
					def workflow_absolute_path = new File(dir.absolutePath, workflow_relative_path).absolutePath

					// Push the workflow to the bucket
					def push_wkw_cmd = [ "bash", "-c", "curl -X POST --header 'Content-Type: multipart/form-data' --header 'Accept: application/json' '" + workflow_catalog_url + "/buckets/" + bucket_id + "/workflows' -F 'file=@" + workflow_absolute_path + "'"]
					println push_wkw_cmd
					push_wkw_cmd.execute().waitForProcessOutput(System.out, System.err)
					
					if (object.get("expose_to_studio") == "yes")
					{
						// Create a new template dir in the targeted directory and copy the wkw into it
						def template_dir = new File(workflow_templates_dir, template_dir_name)
						template_dir.mkdir()
						def file_dest = new File(template_dir, workflow_file_name)	
						def file_dest_path = file_dest.absolutePath
						Files.copy(Paths.get(workflow_absolute_path), Paths.get(file_dest_path), StandardCopyOption.REPLACE_EXISTING)
						
						template_dir_name = (template_dir_name.toInteger() + 1) + ""
					}
				}
				
			}
			
		}
	}